#!/bin/bash
# 官方工具链完整构建：aapt2 → javac → d8 → 打包 → zipalign → apksig签名 → 验证
set -e

WORK=/data/workspace/AICodeStudio_Build
AAPT2=/data/workspace/official-tools/aapt2_extracted/aapt2
AJ=/data/workspace/android-sdk/platforms/android-30/android.jar
R8=$WORK/tools/r8.jar
JDK=/data/user_persistent_data/tools/jdk
BTDIR=/data/user_persistent_data/tools/android-sdk/build-tools/29.0.3
ZIPALIGN=$BTDIR/zipalign
SIGDIR=/data/workspace/official-tools

export LD_LIBRARY_PATH=$BTDIR/lib64:$BTDIR/lib
export LD_PRELOAD=$BTDIR/lib64/libbacktrace_wrapper.so

CP="$AJ"
LIBS=""

cd $WORK
for d in build/gen build/classes build/dex; do [ -d "$d" ] && mv "$d" /data/workspace/.trash_$(basename $d)_$(date +%s%N) 2>/dev/null; done; rm -f build/res.zip build/resources.ap_ 2>/dev/null
mkdir -p build/gen build/classes build/dex

echo "===== [1/7] aapt2 compile (官方 2.19) ====="
$AAPT2 compile --dir src/main/res -o build/res.zip 2>&1 | grep -v compressed | tail -3
echo "  res.zip = $(stat -c%s build/res.zip) bytes"

echo ""
echo "===== [2/7] aapt2 link → resources.ap_ + R.java ====="
$AAPT2 link -o build/resources.ap_ build/res.zip \
  -I $AJ \
  --manifest src/main/AndroidManifest.xml \
  --java build/gen \
  --min-sdk-version 26 --target-sdk-version 30 2>&1 | grep -v compressed | tail -5
echo "  resources.ap_ = $(stat -c%s build/resources.ap_) bytes"
echo "  R.java = $(grep -c 'public static final int' build/gen/com/aicodestudio/ide/R.java) 常量"

echo ""
echo "===== [3/7] javac 编译 ====="
find src/main/java -name "*.java" > build/sources.txt
find build/gen -name "R.java" >> build/sources.txt
$JDK/bin/javac -source 1.8 -target 1.8 -encoding UTF-8 -nowarn \
  -classpath "$CP" -d build/classes @build/sources.txt > build/javac.log 2>&1
JAVAC_RC=$?
head -15 build/javac.log
if [ $JAVAC_RC -ne 0 ]; then
  echo ""
  echo "!! javac 编译失败（退出码 $JAVAC_RC），已中止构建。"
  echo "!! 若继续打包，产出的 APK 会沿用上一轮的旧 classes，"
  echo "!! 表现是『改了代码但 APK 行为没变』，极难排查。"
  exit 1
fi
echo "  class 文件数: $(find build/classes -name '*.class' | wc -l)"

echo ""
echo "===== [4/7] d8 (官方 R8) → classes.dex ====="
CLASSLIST=$(find build/classes -name "*.class" | tr '\n' ' ')
timeout 900 /usr/bin/java -Xmx1536m -cp $R8 com.android.tools.r8.D8 \
  --min-api 26 --lib $AJ --output build/dex $CLASSLIST $LIBS 2>&1 | tail -5
echo "  classes.dex = $(stat -c%s build/dex/classes.dex) bytes"

echo ""
echo "===== [5/7] 打包 APK (标准 DEFLATED，禁止 extra field 填充) ====="
# 重要：不要再用 align_zip.py 往 extra field 塞 0x00 做 4096 页对齐。
# 那个 hack 会产出几千字节的非法 extra field，标准解析器能糊弄过去，
# 但 vivo/OriginOS 的安装校验会直接判 APK 非法（安装失败 / 兼容性报错）。
# 代价是 .so 由系统解压安装（多占约 21MB 存储），换来的是标准兼容。

# 统计 jniLibs 里的 .so
# 注意：jniLibs 只放 llama.cpp 基础依赖库（libllama/libggml*/libomp），
# 自研 JNI 桥接层 libllama_jni.so 需用 tools/build-termux.sh 单独编译。
# 没有 libllama_jni.so 时本地推理不可用，但 App 内仍可通过「导入推理引擎」加载。
SO_COUNT=$(find $WORK/src/main/jniLibs -name "*.so" 2>/dev/null | wc -l)
HAS_JNI=$(find $WORK/src/main/jniLibs -name "libllama_jni.so" 2>/dev/null | wc -l)
if [ "$SO_COUNT" -gt 0 ]; then
  echo "  内置 native 库: $SO_COUNT 个"
  find $WORK/src/main/jniLibs -name "*.so" -printf "    %f  (%s bytes)\n" 2>/dev/null
  if [ "$HAS_JNI" -eq 0 ]; then
    echo "  ⚠ 缺少 libllama_jni.so（自研 JNI 桥接层），本地推理需 App 内导入引擎"
    echo "    编译方法：在 Termux 执行 tools/build-termux.sh"
  fi
else
  echo "  内置 native 库: 无 (本地推理不可用，App 内仍可手动导入引擎)"
fi

python3 $WORK/tools/pack_std.py \
  $WORK/build/resources.ap_ \
  $WORK/build/dex/classes.dex \
  $WORK/src/main/jniLibs \
  $WORK/build/app-unsigned.apk arm64-v8a

echo ""
echo "===== [6/7] zipalign 对齐 ====="
rm -f build/app-aligned.apk
$ZIPALIGN -p 4 build/app-unsigned.apk build/app-aligned.apk 2>&1 | tail -3
echo "  app-aligned.apk = $(stat -c%s build/app-aligned.apk) bytes"

echo ""
echo "===== [7/7] 官方 apksig 签名 (V1+V2+V3) ====="
cd $SIGDIR
timeout 400 $JDK/bin/java -Xmx1024m -cp ".:bundletool.jar:guava.jar" OfficialApkSigner \
  $WORK/build/app-aligned.apk \
  $WORK/build/AI_Code_Studio.apk \
  $WORK/build/aicode.keystore \
  aicode aicode123 aicode123 2>&1 | tail -12

echo ""
echo "===== 官方 ApkVerifier 验证 ====="
timeout 300 $JDK/bin/java -Xmx1024m -cp ".:bundletool.jar:guava.jar" OfficialApkVerifier \
  "$WORK/build/AI_Code_Studio.apk" 2>&1 | tail -25
