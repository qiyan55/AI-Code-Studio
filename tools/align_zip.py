#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
align_zip.py —— 带 4096 页对齐的 APK 打包器

为什么需要自己写而不能用 zipfile:
    当 Manifest 声明 android:extractNativeLibs="false" 时，
    Android 会直接从 APK 里 mmap .so，不再解压到 /data/app/.../lib。
    这要求 .so 在 APK 中同时满足两个条件:
        1. STORED (不压缩)  —— 压缩了就无法 mmap
        2. 数据起始偏移 4096 对齐 (页对齐)
    否则系统会加载失败，或静默回退到解压模式(白白多占一份空间)。

    Python 的 zipfile 无法精确控制条目的数据偏移，
    而官方 zipalign -p 4 只做 4 字节对齐，达不到 mmap 的要求。
    所以这里手写 ZIP 写入器，通过调整 extra field 长度来推齐偏移。

extra field 填充原理:
    local header 结构 = 30字节固定头 + 文件名 + extra
    数据起始 = 条目偏移 + 30 + len(文件名) + len(extra)
    前三项都是定值，唯一可变的就是 extra 长度。
    所以 extra_len = (align - 数据起始) % align  即可精确推齐。
"""
import struct
import zlib
import os
import sys

ZIP_LOCAL_SIG = 0x04034b50
ZIP_CENTRAL_SIG = 0x02014b50
ZIP_EOCD_SIG = 0x06054b50

DEFAULT_ALIGN = 4096


class Entry(object):
    """一个待写入的 ZIP 条目"""

    def __init__(self, name, data, compress=True, align=0):
        self.name = name
        self.raw = data
        self.compress = compress
        self.align = align
        self.crc = 0
        self.method = 0
        self.payload = b""
        self.offset = 0
        self.extra = b""

    def prepare(self):
        """计算 crc / 压缩方式 / 实际写入的数据"""
        self.crc = zlib.crc32(self.raw) & 0xFFFFFFFF
        if self.compress:
            co = zlib.compressobj(9, zlib.DEFLATED, -15)
            body = co.compress(self.raw) + co.flush()
            # 压缩反而变大时(如已压缩的 png/dex)直接存原始
            if len(body) < len(self.raw):
                self.method = 8
                self.payload = body
            else:
                self.method = 0
                self.payload = self.raw
        else:
            self.method = 0
            self.payload = self.raw


class ZipWriter(object):
    def __init__(self):
        self.entries = []

    def add(self, name, data, compress=True, align=0):
        e = Entry(name, data, compress, align)
        e.prepare()
        self.entries.append(e)
        return e

    def add_file(self, name, path, compress=True, align=0):
        with open(path, "rb") as f:
            return self.add(name, f.read(), compress, align)

    def build(self, out_path):
        buf = bytearray()
        central = bytearray()

        for e in self.entries:
            name_b = e.name.encode("utf-8")
            # ---- 计算 padding: 让数据起始落在 align 边界上 ----
            base = len(buf) + 30 + len(name_b)
            if e.align > 1:
                pad = (e.align - (base % e.align)) % e.align
            else:
                pad = 0
            e.extra = b"\x00" * pad
            e.offset = len(buf)

            # ---- local file header ----
            buf += struct.pack(
                "<IHHHHHIIIHH",
                ZIP_LOCAL_SIG,
                20,                # version needed
                0x0800,            # flags: UTF-8 文件名
                e.method,
                0,                 # mod time
                0x21,              # mod date (1980-01-01)
                e.crc,
                len(e.payload),
                len(e.raw),
                len(name_b),
                len(e.extra),
            )
            buf += name_b
            buf += e.extra
            buf += e.payload

            # ---- central directory header ----
            central += struct.pack(
                "<IHHHHHHIIIHHHHHII",
                ZIP_CENTRAL_SIG,
                0x031E,            # version made by (UNIX)
                20,                # version needed
                0x0800,
                e.method,
                0,
                0x21,
                e.crc,
                len(e.payload),
                len(e.raw),
                len(name_b),
                len(e.extra),
                0,                 # comment len
                0,                 # disk number
                0,                 # internal attrs
                (0o100644 << 16),  # external attrs
                e.offset,
            )
            central += name_b
            central += e.extra

        cd_offset = len(buf)
        cd_size = len(central)
        buf += central

        # ---- end of central directory ----
        buf += struct.pack(
            "<IHHHHIIH",
            ZIP_EOCD_SIG,
            0,
            0,
            len(self.entries),
            len(self.entries),
            cd_size,
            cd_offset,
            0,
        )

        with open(out_path, "wb") as f:
            f.write(bytes(buf))
        return len(buf)


def build_apk(resources_apk, dex_path, jnilibs_dir, out_path,
              abi="arm64-v8a", verbose=True):
    """
    组装 APK:
      resources.ap_ 里的资源  →  DEFLATED (4 字节对齐由 aapt2 保证)
      classes.dex             →  DEFLATED
      lib/<abi>/*.so          →  STORED   + 4096 对齐
    """
    zw = ZipWriter()
    counts = {"res": 0, "so": 0}

    # ---- 1. 资源 ----
    import zipfile
    if os.path.exists(resources_apk):
        zin = zipfile.ZipFile(resources_apk, "r")
        for item in zin.infolist():
            if item.is_dir():
                continue
            zw.add(item.filename, zin.read(item.filename), compress=True)
            counts["res"] += 1
        zin.close()

    # ---- 2. dex ----
    zw.add_file("classes.dex", dex_path, compress=True)

    # ---- 3. native libs: STORED + 4096 对齐 ----
    so_dir = os.path.join(jnilibs_dir, abi)
    if os.path.isdir(so_dir):
        names = sorted(os.listdir(so_dir))
        for n in names:
            if not n.endswith(".so"):
                continue
            full = os.path.join(so_dir, n)
            if not os.path.isfile(full):
                continue
            zw.add_file("lib/%s/%s" % (abi, n), full,
                        compress=False, align=DEFAULT_ALIGN)
            counts["so"] += 1

    size = zw.build(out_path)

    if verbose:
        print("  条目: 资源 %d + classes.dex + .so %d" % (counts["res"], counts["so"]))
        print("  APK 大小: %d bytes" % size)
        for e in zw.entries:
            if e.align:
                data_start = e.offset + 30 + len(e.name) + len(e.extra)
                ok = (data_start % DEFAULT_ALIGN) == 0
                print("    [对齐] %-42s offset=%-9d %s"
                      % (e.name, data_start, "OK" if ok else "!! 未对齐"))
    return counts


if __name__ == "__main__":
    if len(sys.argv) < 5:
        print(__doc__)
        print("用法: align_zip.py <resources.ap_> <classes.dex> <jniLibs> <out.apk> [abi]")
        sys.exit(1)
    abi = sys.argv[5] if len(sys.argv) > 5 else "arm64-v8a"
    build_apk(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], abi)
