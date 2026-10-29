#!/bin/bash
# =============================================================================
# AI Code Studio 本地构建脚本
# 用法: ./build_local.sh [项目目录]
#
# 此脚本用于在服务器端或 Linux 环境验证项目结构和 Java 编译。
# 完整 APK 构建（含 native libs + assets 打包）在设备端由 BuiltinCompiler 完成。
# 服务器端完整构建请使用 build_official_all.sh
# =============================================================================

set -e

PROJECT_DIR=${1:-.}
OUTPUT_DIR="$PROJECT_DIR/build_local"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "========================================"
echo "  AI Code Studio 本地构建"
echo "========================================"
echo "项目目录: $PROJECT_DIR"
echo "输出目录: $OUTPUT_DIR"
echo ""

# ---- 1. 检查项目结构 ----
echo "[1/5] 检查项目结构..."
ERRORS=0

check_file() {
    if [ -f "$1" ]; then
        echo "  ✓ $2"
    else
        echo "  ✗ $2 (缺失: $1)"
        ERRORS=$((ERRORS + 1))
    fi
}

check_dir() {
    if [ -d "$1" ]; then
        echo "  ✓ $2 ($(find "$1" -type f | wc -l) 文件)"
    else
        echo "  ✗ $2 (缺失: $1)"
        ERRORS=$((ERRORS + 1))
    fi
}

check_file "$PROJECT_DIR/AndroidManifest.xml" "AndroidManifest.xml"
check_dir "$PROJECT_DIR/src/main/java" "Java 源码"
check_dir "$PROJECT_DIR/src/main/res" "资源文件"
check_dir "$PROJECT_DIR/src/main/jniLibs" "Native 库（预编译 .so）"
check_dir "$PROJECT_DIR/src/main/assets" "Assets 资源"

if [ -d "$PROJECT_DIR/src/main/jniLibs/arm64-v8a" ]; then
    SO_COUNT=$(find "$PROJECT_DIR/src/main/jniLibs/arm64-v8a" -name "*.so" | wc -l)
    SO_SIZE=$(du -sh "$PROJECT_DIR/src/main/jniLibs/arm64-v8a" | cut -f1)
    echo "    → arm64-v8a: $SO_COUNT 个 .so，共 $SO_SIZE"
fi

if [ -d "$PROJECT_DIR/src/main/assets" ]; then
    ASSET_SIZE=$(du -sh "$PROJECT_DIR/src/main/assets" | cut -f1)
    echo "    → assets 总大小: $ASSET_SIZE"
fi

if [ $ERRORS -gt 0 ]; then
    echo ""
    echo "错误: 项目结构检查失败，共 $ERRORS 项缺失"
    exit 1
fi
echo "  项目结构检查通过"
echo ""

# ---- 2. 检查编译工具链 ----
echo "[2/5] 检查编译工具链..."

# 查找 ecj.jar（优先项目内，其次系统）
ECJ_JAR=""
if [ -f "$PROJECT_DIR/src/main/assets/compile/ecj.jar" ]; then
    ECJ_JAR="$PROJECT_DIR/src/main/assets/compile/ecj.jar"
elif [ -f "$SCRIPT_DIR/libs/ecj.jar" ]; then
    ECJ_JAR="$SCRIPT_DIR/libs/ecj.jar"
elif command -v ecj &> /dev/null; then
    ECJ_JAR=$(which ecj)
fi

# 查找 android.jar
ANDROID_JAR=""
if [ -f "$PROJECT_DIR/src/main/assets/compile/android.jar" ]; then
    ANDROID_JAR="$PROJECT_DIR/src/main/assets/compile/android.jar"
elif [ -n "$ANDROID_HOME" ] && [ -f "$ANDROID_HOME/platforms/android-34/android.jar" ]; then
    ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
fi

# 查找 androidx-all.jar
ANDROIDX_JAR=""
if [ -f "$PROJECT_DIR/src/main/assets/compile/androidx-all.jar" ]; then
    ANDROIDX_JAR="$PROJECT_DIR/src/main/assets/compile/androidx-all.jar"
elif [ -f "$SCRIPT_DIR/libs/androidx-all.jar" ]; then
    ANDROIDX_JAR="$SCRIPT_DIR/libs/androidx-all.jar"
fi

echo "  ECJ: ${ECJ_JAR:-未找到（跳过Java编译）}"
echo "  android.jar: ${ANDROID_JAR:-未找到}"
echo "  androidx-all.jar: ${ANDROIDX_JAR:-未找到}"
echo ""

# ---- 3. 编译 Java 源码 ----
if [ -n "$ECJ_JAR" ] && [ -n "$ANDROID_JAR" ]; then
    echo "[3/5] 编译 Java 源码..."
    mkdir -p "$OUTPUT_DIR/classes"

    JAVA_FILES=$(find "$PROJECT_DIR/src/main/java" -name "*.java")
    JAVA_COUNT=$(echo "$JAVA_FILES" | wc -l)
    echo "  编译 $JAVA_COUNT 个 Java 文件..."

    CLASSPATH="$ANDROID_JAR"
    [ -n "$ANDROIDX_JAR" ] && CLASSPATH="$CLASSPATH:$ANDROIDX_JAR"

    if java -jar "$ECJ_JAR" \
        -bootclasspath "$ANDROID_JAR" \
        -classpath "$CLASSPATH" \
        -d "$OUTPUT_DIR/classes" \
        -source 1.8 -target 1.8 \
        -nowarn \
        $JAVA_FILES 2> "$OUTPUT_DIR/compile_errors.log"; then
        CLASS_COUNT=$(find "$OUTPUT_DIR/classes" -name "*.class" | wc -l)
        echo "  ✓ Java 编译完成，生成 $CLASS_COUNT 个 .class 文件"
    else
        echo "  ✗ Java 编译失败，错误日志: $OUTPUT_DIR/compile_errors.log"
        tail -30 "$OUTPUT_DIR/compile_errors.log"
        exit 1
    fi
else
    echo "[3/5] 跳过 Java 编译（缺少 ECJ 或 android.jar）"
fi
echo ""

# ---- 4. 统计项目信息 ----
echo "[4/5] 统计项目信息..."
TOTAL_JAVA=$(find "$PROJECT_DIR/src/main/java" -name "*.java" | wc -l)
TOTAL_LINES=$(find "$PROJECT_DIR/src/main/java" -name "*.java" -exec cat {} + | wc -l)
TOTAL_RES=$(find "$PROJECT_DIR/src/main/res" -type f | wc -l)
TOTAL_SO=$(find "$PROJECT_DIR/src/main/jniLibs" -name "*.so" 2>/dev/null | wc -l)
TOTAL_ASSETS=$(find "$PROJECT_DIR/src/main/assets" -type f 2>/dev/null | wc -l)

echo "  Java 文件: $TOTAL_JAVA 个，$TOTAL_LINES 行"
echo "  资源文件: $TOTAL_RES 个"
echo "  Native 库: $TOTAL_SO 个 .so"
echo "  Assets 文件: $TOTAL_ASSETS 个"
echo ""

# ---- 5. 输出构建指南 ----
echo "[5/5] 设备端自举编译指南"
echo ""
echo "  本脚本只完成 Java 编译验证。完整 APK 构建请在设备端进行："
echo ""
echo "  1. 将源码包放入手机存储，例如:"
echo "     /sdcard/AI_Code_Studio_source/"
echo ""
echo "  2. 打开 AI Code Studio APP"
echo ""
echo "  3. 切换到「编辑器」Tab，打开项目目录"
echo ""
echo "  4. 切换到「对话」Tab，输入:"
echo "     aics compile"
echo ""
echo "  5. 等待编译完成（约 1-3 分钟）"
echo "     - ECJ 编译 Java 源码"
echo "     - d8 转 DEX"
echo "     - aapt2 资源编译"
echo "     - 打包 native libs + assets（v6.2+）"
echo "     - apksigner 签名"
echo ""
echo "  6. APK 输出到:"
echo "     /data/data/com.aicodestudio.ide.ai/cache/compile_apk/"
echo ""
echo "  7. 安装测试:"
echo "     aics run"
echo ""
echo "========================================"
echo "  构建验证完成"
echo "========================================"
