#!/usr/bin/env python3
# -*- coding: utf-8 -*-
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
    assets_dir = "src/main/assets" # 新增：自动读取此目录

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
        for root, _, files in os.walk(jni_dir):
            rel = os.path.relpath(root, jni_dir)
            entry_abi = rel if rel != "." else abi
            if entry_abi.startswith("..") or "/" in entry_abi:
                entry_abi = abi
            for f in sorted(files):
                if not f.endswith(".so"):
                    continue
                full = os.path.join(root, f)
                z_out.write(full, "lib/%s/%s" % (entry_abi, f), zipfile.ZIP_STORED)
                n_so += 1

    z_out.close()

    print("  资源条目 %d, .so %d 个" % (n_res, n_so))
    print("  unsigned APK = %d bytes (%.2f MB)" % (
        os.path.getsize(out), os.path.getsize(out) / 1048576.0))
    return 0

if __name__ == "__main__":
    sys.exit(main())
