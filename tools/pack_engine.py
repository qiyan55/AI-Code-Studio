#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
pack_engine.py —— 把编译好的 .so 装进 APK 源码树

用法:
    python3 pack_engine.py <so所在目录或zip> [abi]

做三件事:
    1. 校验每个 .so 确实是 AArch64 共享库
       (误把 x86_64 的库打进 APK，装到手机上会直接崩，必须拦在编译前)
    2. 拷贝进 src/main/jniLibs/<abi>/
    3. 检查 llama.cpp 的 6 个库是否齐全，缺哪个明说

llama.cpp 新版拆成多个库，且相互有 NEEDED 依赖，
少一个就会导致 UnsatisfiedLinkError，所以必须成套。
"""
import os
import sys
import shutil
import struct
import zipfile
import tempfile

WORK = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JNILIBS = os.path.join(WORK, "src", "main", "jniLibs")
DEFAULT_ABI = "arm64-v8a"

# llama.cpp 完整依赖链(加载顺序即依赖顺序)
REQUIRED = [
    "libggml-base.so",
    "libggml-cpu.so",
    "libggml.so",
    "libllama.so",
    "libllama_jni.so",   # 主库(JNI 入口)
]
OPTIONAL = ["libmtmd.so"]   # 多模态，纯文本推理可不要

EM_AARCH64 = 0xB7
EM_X86_64 = 0x3E
EM_ARM = 0x28
EM_386 = 0x03

MACHINE_NAME = {
    EM_AARCH64: "AArch64 (arm64-v8a)",
    EM_X86_64: "x86-64",
    EM_ARM: "ARM 32-bit (armeabi-v7a)",
    EM_386: "x86 32-bit",
}


def probe_elf(path):
    """读 ELF 头，返回 (架构码, 位数, 是否共享库, 说明)"""
    try:
        with open(path, "rb") as f:
            head = f.read(64)
    except Exception as e:
        return None, None, None, "读取失败: %s" % e

    if len(head) < 20 or head[:4] != b"\x7fELF":
        return None, None, None, "不是 ELF 文件"

    ei_class = head[4]        # 1=32bit 2=64bit
    ei_data = head[5]         # 1=小端 2=大端
    e_type = struct.unpack_from("<H", head, 16)[0]
    e_machine = struct.unpack_from("<H", head, 18)[0]

    if ei_data != 1:
        return e_machine, ei_class, False, "大端序，Android 不支持"

    bits = 64 if ei_class == 2 else 32
    is_dyn = (e_type == 3)  # ET_DYN = 共享库
    return e_machine, bits, is_dyn, "ok"


def collect_sos(src, tmpdir):
    """从目录或 zip 收集 .so，返回 {文件名: 实际路径}"""
    found = {}
    if os.path.isdir(src):
        for name in os.listdir(src):
            if name.endswith(".so"):
                found[name] = os.path.join(src, name)
        # 常见子目录也找一层
        if not found:
            for sub in os.listdir(src):
                d = os.path.join(src, sub)
                if os.path.isdir(d):
                    for name in os.listdir(d):
                        if name.endswith(".so"):
                            found[name] = os.path.join(d, name)
    elif os.path.isfile(src) and src.lower().endswith(".zip"):
        z = zipfile.ZipFile(src)
        for item in z.infolist():
            if item.filename.endswith(".so") and not item.is_dir():
                base = os.path.basename(item.filename)
                out = os.path.join(tmpdir, base)
                with open(out, "wb") as f:
                    f.write(z.read(item.filename))
                found[base] = out
        z.close()
    return found


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    src = sys.argv[1]
    abi = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_ABI

    if not os.path.exists(src):
        print("!! 路径不存在: %s" % src)
        sys.exit(1)

    print("源: %s" % src)
    print("目标 ABI: %s" % abi)
    print("")

    tmpdir = tempfile.mkdtemp()
    sos = collect_sos(src, tmpdir)

    if not sos:
        print("!! 没找到任何 .so 文件")
        print("   请确认目录里确实有编译产物")
        sys.exit(1)

    # ---- 1. 架构校验 ----
    print("===== [1/3] 架构校验 =====")
    good = {}
    skipped = []
    for name in sorted(sos):
        machine, bits, is_dyn, note = probe_elf(sos[name])
        if machine == EM_AARCH64 and bits == 64:
            tag = "共享库" if is_dyn else "可执行文件(非.so?)"
            print("  [OK]   %-22s AArch64 64-bit %s  %d bytes"
                  % (name, tag, os.path.getsize(sos[name])))
            good[name] = sos[name]
        else:
            mn = MACHINE_NAME.get(machine, "未知(0x%X)" % (machine or 0))
            if note != "ok":
                mn += " / " + note
            print("  [跳过] %-22s %s" % (name, mn))
            skipped.append((name, mn))
    print("")

    if not good:
        print("!! 没有可用的 AArch64 库")
        print("   你给的可能是 x86/PC 版本，装到手机上必然崩溃。")
        print("   请用 Termux 或 GitHub Actions 编 arm64 版本。")
        sys.exit(1)

    # ---- 2. 拷贝 ----
    print("===== [2/3] 拷贝到 jniLibs =====")
    dest = os.path.join(JNILIBS, abi)
    if os.path.isdir(dest):
        old = [f for f in os.listdir(dest) if f.endswith(".so")]
        if old:
            print("  清空旧库: %s" % ", ".join(old))
            for f in old:
                os.remove(os.path.join(dest, f))
    os.makedirs(dest, exist_ok=True)

    for name, path in sorted(good.items()):
        shutil.copy2(path, os.path.join(dest, name))
        print("  → lib/%s/%s" % (abi, name))
    print("")

    # ---- 3. 完整性检查 ----
    print("===== [3/3] 依赖完整性 =====")
    have = set(good.keys())
    missing = [r for r in REQUIRED if r not in have]
    if missing:
        print("  !! 缺少必需库: %s" % ", ".join(missing))
        print("     加载时会报 UnsatisfiedLinkError")
    else:
        print("  [OK] 必需库齐全 (%d/%d)" % (len(REQUIRED), len(REQUIRED)))

    opt_missing = [o for o in OPTIONAL if o not in have]
    if opt_missing:
        print("  [提示] 可选库缺失: %s (纯文本推理不需要)" % ", ".join(opt_missing))

    if skipped:
        print("")
        print("  被跳过的文件:")
        for name, mn in skipped:
            print("    %-22s %s" % (name, mn))

    print("")
    print("下一步: 运行 build_official_all.sh 重新打包 APK")
    shutil.rmtree(tmpdir, ignore_errors=True)
    return 0 if not missing else 2


if __name__ == "__main__":
    sys.exit(main())
