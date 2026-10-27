# AI Code Studio 源码包

## 目录结构

```
AI_Code_Studio_源码/
├── src/
│   ├── main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/aicodestudio/ide/ai/    ← Java 源文件
│   │   ├── res/                             ← 资源（drawable/anim/layout/values…）
│   │   └── jniLibs/arm64-v8a/               ← llama.cpp 基础依赖 .so
│   └── ...
├── libs/                                    ← 第三方 jar/aar
├── tools/
│   ├── pack_std.py          标准打包（当前在用）
│   ├── pack_engine.py       引擎 .so 内置工具（带架构自检）
│   ├── align_zip.py         4096 页对齐【已弃用，会导致安装失败】
│   ├── build-termux.sh      Termux 手机端编译 llama.cpp + JNI 桥接层
│   └── llama_jni.c          自研 JNI 桥接层 C 源码（直连 llama.cpp 核心 API）
└── build_official_all.sh    一键构建脚本
```

## 构建步骤

```bash
cd AI_Code_Studio_源码
bash build_official_all.sh
```

产物：同目录下 `AI_Code_Studio.apk`

流程：aapt2 编译资源 → javac 编译 → d8 转 DEX → zipalign 对齐 → apksig 签名 → ApkVerifier 校验

## 源码分部

| 包 | 内容 |
|---|---|
| `engine/` | InferenceEngine 接口、LlamaCppEngine（自研 JNI 直连）、DeviceProfile、ModelAdmission、GgufProbe、EngineManager、NativeLibraryLoader |
| `models/` | LocalModelManager、ModelManageDialog、ModelManageActivity |
| `ui/` | AppleDialog、Theme、UiKit、Widgets、DialogAnim |
| `tools/` | BuildToolManager 及管理弹窗 |
| `chat/` `editor/` `compile/` `api/` | 对话、编辑器、编译执行、云端接口 |
| `util/` | StorageAccessActivity（存储权限代理）等 |

## 环境依赖

脚本内路径写死为沙盒环境，换机器需修改 `build_official_all.sh` 顶部：

| 变量 | 当前值 | 说明 |
|---|---|---|
| `WORK` | `/data/workspace/AICodeStudio_Build` | 源码根目录 |
| `BTDIR` | `/data/user_persistent_data/tools/android-sdk/build-tools/29.0.3` | Android build-tools |
| `SIGDIR` | `/data/workspace/official-tools` | apksig / ApkVerifier |

还需要：JDK 8+（javac）、d8、zipalign、apksig。

## 已知坑（重要）

**1. 不要用 `align_zip.py`**

它往 ZIP extra field 塞 4335 字节 0x00 做 4096 页对齐。虽然通用解析器能读，但 vivo OriginOS 的定制校验会判定 APK 非法，报「与系统不兼容」安装失败。

当前 `AndroidManifest.xml` 中 `extractNativeLibs="true"`（系统解压模式），用官方 `zipalign -p 4` 即可，extra field 最大 3 字节。

**2. `javac` 不要接管道**

`javac ... | head -15` 会把 javac 的退出码吞掉，导致 `set -e` 失效——编译失败仍继续打包，产出沿用旧 classes 的"幽灵 APK"，表现为「改了代码但装上去没变化」。必须先落日志再判退出码。

**3. 引擎 .so 需要单独编译**

`jniLibs/arm64-v8a/` 里只放了 llama.cpp 的基础依赖库（libllama.so / libggml*.so / libomp.so），**不包含自研 JNI 桥接层 `libllama_jni.so`**。

要让本地推理可用，必须先用 `tools/build-termux.sh` 在手机上编译出完整引擎（含 `libllama_jni.so`），然后二选一：
- **方式A（推荐）**：App 内「导入推理引擎」直接加载，无需重打包
- **方式B**：把编译产物拷进 `jniLibs/arm64-v8a/` 后重新构建 APK

## 引擎架构（自研 JNI 直连 llama.cpp）

本项目通过自研 `tools/llama_jni.c` 直接调用 llama.cpp 核心 C API，不经过第三方封装层。

### 依赖链

```
libomp.so → libggml-base.so → libggml-cpu.so → libggml.so → libllama.so → libllama_jni.so
```

### 已支持的高级特性

| 特性 | 说明 |
|---|---|
| **prefix cache** | `warmPrefix()` / `clearPrefixCache()` / `cacheStats()` — 系统提示词 KV 缓存复用 |
| **KV cache 精度** | FP16 / Q8 / Q4 三档可调，内存紧张时降档换上下文长度 |
| **生成参数实时调节** | temperature / top-k / top-p 每次生成独立生效 |
| **mmap 预热** | `prefetchModel()` 顺序拉页，改善冷启动首 token 延迟 |
| **native 后端汇报** | 编译期宏直接汇报 CPU 指令集/内核启用状态 |
| **多目录引擎加载** | APK 内置 → App 内部目录 → Termux 输出 → 外部存储，自动搜索 |

### JNI 方法与 llama_jni.c 对应表

| Java native 方法 | C 函数 | 功能 |
|---|---|---|
| `nVersion()` | `Java_..._nVersion` | llama.cpp 版本号 |
| `nBackendInfo()` | `Java_..._nBackendInfo` | CPU/指令集/内核信息 |
| `nLoad(...)` | `Java_..._nLoad` | 加载模型，返回会话句柄 |
| `nUnload(handle)` | `Java_..._nUnload` | 释放模型资源 |
| `nStop(handle)` | `Java_..._nStop` | 中断生成 |
| `nGenerate(...)` | `Java_..._nGenerate` | 同步/流式生成（通过 onToken 回调） |
| `nWarmPrefix(...)` | `Java_..._nWarmPrefix` | 前缀缓存预热 |
| `nClearCache(handle)` | `Java_..._nClearCache` | 清空 KV cache |
| `nCacheStats(handle)` | `Java_..._nCacheStats` | KV cache 使用统计 |
| `nPrefetch(handle)` | `Java_..._nPrefetch` | mmap 页预热 |

### 编译引擎（Termux 手机端）

```bash
# 1. 安装 Termux（F-Droid 版本）
# 2. 授权存储：termux-setup-storage
# 3. 把 build-termux.sh 和 llama_jni.c 放到 ~/
# 4. 执行：
bash build-termux.sh
```

产物：`~/llama-out/arm64-v8a/` 下的完整引擎（含 `libllama_jni.so` 及所有依赖）。

## 未落地项

- 投机解码（EAGLE/Medusa）
- Vulkan / NPU 后端
- 自研 ARM SIMD 内核
- repeat_penalty / stop 序列在 native 侧的实现（当前参数已透传，采样逻辑待补）
