#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
pack_std.py —— 标准方式打包 APK

与 align_zip.py 的区别：
  align_zip.py 为实现「免解压 mmap」往 ZIP 的 extra field 里塞大量 0x00 做
  4096 字节页对齐，产出几千字节的非法 extra field。通用解析器能糊弄过去，
  但 vivo / OriginOS 的安装校验更严格，会直接判定 APK 非法导致安装失败。

本脚本改为标准做法：
  - 所有条目一律 DEFLATED（.so 也压缩，APK 体积反而更小）
  - 不写任何自定义 extra field（仅保留 zipfile 必要的 0~3 字节）
  - 页对齐交给后续官方 zipalign -p 4 处理

对应 manifest 中 android:extractNativeLibs="true"（由系统解压 .so）。
"""
import os
import sys
import zipfile


def main():
    if len(sys.argv) < 5:
        print("用法: pack_std.py <resources.ap_> <classes.dex> <jniLibsDir> <out.apk> [abi]")
        return 1

    res_apk = sys.argv[1]
    dex = sys.argv[2]
    jni_dir = sys.argv[3]
    out = sys.argv[4]
    abi = sys.argv[5] if len(sys.argv) > 5 else "arm64-v8a"

    if os.path.exists(out):
        os.remove(out)

    z_out = zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED)

    n_res = 0
    if os.path.exists(res_apk):
        z_in = zipfile.ZipFile(res_apk)
        for info in z_in.infolist():
            if info.filename.startswith("META-INF"):
                continue
            z_out.writestr(info, z_in.read(info.filename))
            n_res += 1
        z_in.close()

    if os.path.exists(dex):
        z_out.write(dex, "classes.dex", zipfile.ZIP_DEFLATED)
    else:
        print("!! 缺少 classes.dex")
        return 1

    n_so = 0
    if os.path.isdir(jni_dir):
        # 按 jniLibs 子目录名作为 ABI 分组（如 arm64-v8a/、x86_64/），
        # 顶层散落的 .so 归入 abi 参数指定的默认 ABI
        for root, _, files in os.walk(jni_dir):
            rel = os.path.relpath(root, jni_dir)
            entry_abi = rel if rel != "." else abi
            if entry_abi.startswith("..") or "/" in entry_abi:
                entry_abi = abi
            for f in sorted(files):
                if not f.endswith(".so"):
                    continue
                full = os.path.join(root, f)
                # .so 必须 STORED 不压缩：Android 直接 mmap 加载，
                # DEFLATE 压缩的 .so 在部分国产 ROM（vivo 等）上 System.loadLibrary 失败
                z_out.write(full, "lib/%s/%s" % (entry_abi, f), zipfile.ZIP_STORED)
                n_so += 1

    z_out.close()

    print("  资源条目 %d, .so %d 个" % (n_res, n_so))
    print("  unsigned APK = %d bytes (%.2f MB)" % (
        os.path.getsize(out), os.path.getsize(out) / 1048576.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
