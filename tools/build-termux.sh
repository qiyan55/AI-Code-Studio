#!/data/data/com.termux/files/usr/bin/bash
#
# build-termux.sh - 在 Android 手机上原生编译 llama.cpp 引擎
#
# 为什么用 Termux 而不是 NDK 交叉编译：
#   Termux 自带 clang/cmake/git，在设备本地编译，编出来的天然就是 arm64，
#   根本不存在"交叉编译"这一环。省掉 NDK 下载（3.5GB）、省掉交叉工具链配置，
#   而且 -mcpu=native 能精确识别你这颗 SoC 支持的指令集。
#
# 用法：
#   1. 在手机上安装 Termux（推荐 F-Droid 版本，Play 版已停更）
#   2. 首次运行前执行： termux-setup-storage
#   3. 把本脚本和 llama_jni.c 一起放到 ~/ 目录
#   4. bash build-termux.sh
#
# 产物： ~/llama-out/arm64-v8a/ 下的 libllama_jni.so 及其依赖
#        复制到项目的 src/main/jniLibs/arm64-v8a/ 即可
#
# 预计耗时：首次 20~40 分钟（取决于 SoC），手机会发热，属正常现象。

set -e

# ==================== 配置区（可按需修改）====================
WORK_DIR="$HOME/llama-build"
OUT_DIR="$HOME/llama-out"
LLAMA_REPO="https://github.com/ggml-org/llama.cpp.git"
LLAMA_TAG=""                 # 留空=用最新 master；想锁版本填 commit 或 tag
JOBS=4                       # 并行编译数，内存小的机器改 2
ENABLE_KLEIDIAI=ON           # ARM 官方微内核，白拿 10~30%
ENABLE_VULKAN=OFF            # GPU 后端，多数设备收益不稳定，默认关
# ============================================================

Cyan='\033[0;36m'; Green='\033[0;32m'; Yellow='\033[1;33m'; Red='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${Cyan}[$(date +%H:%M:%S)]${NC} $*"; }
ok()   { echo -e "${Green}[OK]${NC} $*"; }
warn() { echo -e "${Yellow}[!]${NC} $*"; }
err()  { echo -e "${Red}[X]${NC} $*"; }

log "=== llama.cpp 引擎编译（Termux 原生 arm64）==="
echo ""

# ---------- 0. 环境自检 ----------
if [ ! -d "/data/data/com.termux/files/usr" ]; then
    err "未检测到 Termux 环境，此脚本只能在 Termux 中运行"
    exit 1
fi

ARCH=$(uname -m)
log "设备架构: $ARCH"
if [ "$ARCH" != "aarch64" ]; then
    warn "当前不是 arm64（$ARCH），编出来的 .so 无法用于 arm64-v8a"
    warn "32 位设备内存通常也不足以跑模型，建议换设备"
fi

MEM_GB=$(awk '/MemTotal/ {printf "%.1f", $2/1024/1024}' /proc/meminfo)
log "设备内存: ${MEM_GB} GB"
if awk "BEGIN{exit !($MEM_GB < 3)}"; then
    warn "内存小于 3GB，并行编译可能失败，自动降低并行度"
    JOBS=2
fi

# ---------- 1. 安装依赖 ----------
log "检查编译依赖..."
NEED=""
for p in clang cmake git make; do
    if ! command -v $p >/dev/null 2>&1; then
        NEED="$NEED $p"
    fi
done
if [ -n "$NEED" ]; then
    log "安装:$NEED"
    pkg update -y
    pkg install -y $NEED
else
    ok "依赖已就绪"
fi

# ---------- 2. 获取 llama.cpp 源码 ----------
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

if [ -d "llama.cpp/.git" ]; then
    log "源码已存在，更新中..."
    cd llama.cpp
    git pull --depth=1 2>/dev/null || warn "更新失败，使用现有代码"
    cd ..
else
    log "克隆 llama.cpp（浅克隆，约 100MB）..."
    if [ -n "$LLAMA_TAG" ]; then
        git clone --depth=1 --branch "$LLAMA_TAG" "$LLAMA_REPO"
    else
        git clone --depth=1 "$LLAMA_REPO"
    fi
fi
ok "源码就绪: $WORK_DIR/llama.cpp"

cd llama.cpp
LLAMA_VER=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
log "版本: $LLAMA_VER"

# ---------- 3. CPU 特性探测（决定优化开关）----------
log "探测 CPU 指令集..."
FEATURES=$(grep -m1 '^Features' /proc/cpuinfo | cut -d: -f2- || echo "")
HAS_DOTPROD=no; HAS_I8MM=no; HAS_FP16=no; HAS_SVE=no

for f in $FEATURES; do
    case "$f" in
        asimddp) HAS_DOTPROD=yes ;;
        i8mm)    HAS_I8MM=yes ;;
        fphp|asimdhp) HAS_FP16=yes ;;
        sve)     HAS_SVE=yes ;;
    esac
done

echo "    NEON  : yes (aarch64 必有)"
echo "    dotprod: $HAS_DOTPROD   <- Q4_0/Q8_0 系列提速关键"
echo "    i8mm  : $HAS_I8MM   <- Q4_K 系列提速关键"
echo "    FP16  : $HAS_FP16"
echo "    SVE   : $HAS_SVE"

if [ "$HAS_DOTPROD" = "no" ] && [ "$HAS_I8MM" = "no" ]; then
    warn "未检测到 dotprod/i8mm，量化模型推理会明显偏慢"
    warn "（这颗 SoC 较老，建议改用 F16 模型或直接用云端）"
fi

# ---------- 4. CMake 配置 ----------
log "配置 CMake..."

# GGML_CPU_ARM_ARCH=native 是关键：
#   ggml 默认保守使用基础 NEON，设成 native 后编译器会按本机 CPU
#   自动开启 dotprod/i8mm/fp16 等指令集，这是最容易拿到的免费提速。
CMAKE_ARGS=(
    -DCMAKE_BUILD_TYPE=Release
    -DBUILD_SHARED_LIBS=ON
    -DLLAMA_BUILD_TESTS=OFF
    -DLLAMA_BUILD_EXAMPLES=OFF
    -DLLAMA_BUILD_SERVER=OFF
    -DLLAMA_CURL=OFF
    -DGGML_NATIVE=ON
    -DGGML_CPU_ARM_ARCH=native
    -DGGML_CPU_KLEIDIAI=$ENABLE_KLEIDIAI
    -DGGML_OPENMP=OFF
    -DGGML_VULKAN=$ENABLE_VULKAN
    -DGGML_LLAMAFILE=OFF
    -DGGML_RPC=OFF
)

if [ -d "build" ] && [ -f "build/CMakeCache.txt" ]; then
    log "复用已有构建目录（如需完全重编，删掉 build 目录）"
else
    rm -rf build
fi

cmake -B build "${CMAKE_ARGS[@]}" 2>&1 | tail -20
ok "CMake 配置完成"

# ---------- 5. 编译 ----------
log "开始编译（$JOBS 线程，约 20~40 分钟，手机会发热）..."
log "提示：可以挂着不管，编译完会有提示音"
cmake --build build --config Release -j "$JOBS" 2>&1 | tail -30
ok "llama.cpp 编译完成"

# ---------- 6. 编译 JNI 桥接层 ----------
log "编译 JNI 桥接层..."

# 找到 llama.cpp 的产物目录（不同版本结构不同）
LIBDIR=""
for d in build build/src build/ggml/src build/src/libllama; do
    if ls "$d"/libllama.so >/dev/null 2>&1; then
        LIBDIR="$d"; break
    fi
done
if [ -z "$LIBDIR" ]; then
    LIBDIR=$(dirname "$(find build -name 'libllama.so' | head -1)" 2>/dev/null)
fi
if [ -z "$LIBDIR" ]; then
    err "未找到 libllama.so，编译可能失败"
    exit 1
fi
log "库目录: $LIBDIR"

# 收集所有 ggml 相关 .so（新版本会拆成多个）
GGML_LIBS=$(find build -name 'libggml*.so' | tr '\n' ' ')
LLAMA_LIBS=$(find build -name 'libllama*.so' | tr '\n' ' ')
log "依赖库: $GGML_LIBS $LLAMA_LIBS"

# 找 JNI 源文件
JNI_SRC=""
for p in "$HOME/llama_jni.c" "$WORK_DIR/llama_jni.c" "$(dirname "$0")/llama_jni.c"; do
    if [ -f "$p" ]; then JNI_SRC="$p"; break; fi
done
if [ -z "$JNI_SRC" ]; then
    err "未找到 llama_jni.c，请把它放到 ~/ 目录"
    exit 1
fi

# 拼链接参数
LINK_ARGS=""
for lib in $GGML_LIBS $LLAMA_LIBS; do
    LINK_ARGS="$LINK_ARGS -L$(dirname $lib) -l$(basename $lib .so | sed 's/^lib//')"
done

clang -shared -fPIC -O3 -march=native \
    -I"$WORK_DIR/llama.cpp/include" \
    -I"$WORK_DIR/llama.cpp/ggml/include" \
    -I"$WORK_DIR/llama.cpp/src" \
    -DGGML_COMMIT=\"termux-$LLAMA_VER\" \
    -DLLAMA_COMMIT=\"$LLAMA_VER\" \
    "$JNI_SRC" \
    $LINK_ARGS \
    -lm -llog \
    -o "$WORK_DIR/libllama_jni.so"

if [ ! -f "$WORK_DIR/libllama_jni.so" ]; then
    err "JNI 库编译失败"
    exit 1
fi
ok "JNI 桥接层编译完成"

# ---------- 7. 组织产物 ----------
log "组织产物..."
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/arm64-v8a"
cp "$WORK_DIR/libllama_jni.so" "$OUT_DIR/arm64-v8a/"
for lib in $GGML_LIBS $LLAMA_LIBS; do
    cp "$lib" "$OUT_DIR/arm64-v8a/"
done

# ---------- 8. 验证 ----------
log "验证产物..."
cd "$OUT_DIR/arm64-v8a"
MISSING=""
for so in *.so; do
    # 检查每个 .so 的依赖是否都能找到
    DEPS=$(readelf -d "$so" 2>/dev/null | grep NEEDED | sed 's/.*\[\(.*\)\]/\1/')
    for dep in $DEPS; do
        case "$dep" in
            libllama*.so|libggml*.so)
                [ -f "$dep" ] || MISSING="$MISSING $dep"
                ;;
        esac
    done
done

if [ -n "$MISSING" ]; then
    err "缺少依赖:$MISSING"
    exit 1
fi

echo ""
log "产物清单:"
ls -lh "$OUT_DIR/arm64-v8a/"
TOTAL=$(du -sh "$OUT_DIR/arm64-v8a" | cut -f1)
ok "总计: $TOTAL"

# ---------- 9. 输出使用说明 ----------
echo ""
echo -e "${Green}========================================${NC}"
echo -e "${Green}  编译成功！${NC}"
echo -e "${Green}========================================${NC}"
echo ""
echo "产物位置: $OUT_DIR/arm64-v8a/"
echo ""
echo "下一步（任选一种，推荐方式A）："
echo ""
echo "  【方式A】App 内直接导入（无需重打包 APK）"
echo "    1. 在 Termux 执行："
echo "         termux-setup-storage        # 若尚未授权存储访问"
echo "         cp -r $OUT_DIR /sdcard/Download/llama-out"
echo "    2. 打开 App → 本地模型 →「导入推理引擎（.so 目录）」"
echo "    3. 浏览到 Download/llama-out/arm64-v8a，点「导入并加载」"
echo "    引擎会拷进 App 内部目录并立即生效，无需重启、无需重新打包。"
echo ""
echo "  【方式B】拷进项目源码重新打包"
echo "    cp $OUT_DIR/arm64-v8a/*.so \\"
echo "       <项目>/src/main/jniLibs/arm64-v8a/"
echo ""
echo -e "${Yellow}注意：${NC}"
echo "  - 首次加载模型建议在 WiFi 环境下（不是下载，是避免 CPU 降频干扰测试）"
echo "  - 编译时手机发热属正常，若担心可降 JOBS=2"
echo "  - 若加载模型时崩溃，说明内存不足，换更小量化档位"
echo ""
ok "完成于 $(date +%H:%M:%S)"
