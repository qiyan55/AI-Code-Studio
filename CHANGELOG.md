# AI Code Studio 变更日志

本文件记录 AI Code Studio 项目的所有重要变更。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

### 新增
- 自举编译支持：BuiltinCompiler 增强打包 jniLibs/*.so 和 assets/
- targetSdk 过低自动升级（<33→34），避免 Android 14+ 兼容性警告
- 项目结构规范文档
- .gitignore、.gitattributes、proguard-rules.pro 等标准配置文件

### 变更
- 闲聊模式系统提示词对齐 MiniCPM5 官方规范
- L1/L2 分离 KV 保存与加载

### 修复
- 修复总结轮误删正常回复的 bug
- 修复后台总结轮流式输出到 UI 的问题

## [1.76] - 2026-09-23

### 新增
- L3 KV 快照检索融合机制
- 后台 Agent 摘要机制
- MiniCPM5 模型适配
- 欢迎页面（WelcomeActivity）

### 变更
- 三层记忆架构（L1 系统提示词 / L2 摘要 / L3 快照）

### 修复
- 修复 KV 缓存复用问题
- 修复滑动窗口导致的 AI 失忆问题

## [1.0] - 2026-09-01

### 新增
- 初始版本发布
- 本地 llama.cpp GGUF 推理引擎
- 基础对话功能
- 代码编辑器
- 内置编译工具链（ECJ + d8 + aapt2 + apksigner）
- 语音识别（sherpa-onnx）
- KV 缓存持久化

---

## 版本说明

- **MAJOR**：不兼容的 API 变更
- **MINOR**：向下兼容的功能性新增
- **PATCH**：向下兼容的问题修正

### 版本号对应关系

| 项目版本 | APK versionCode | APK versionName | 文档版本 |
|---|---|---|---|
| v6.3 | 76 | 1.76 | v2.3 |
| v6.2 | 76 | 1.76 | v2.2 |
| v6.1 | 76 | 1.76 | v2.1 |
| v6.0 | 76 | 1.76 | v2.0 |
| v5.14 | 76 | 1.76 | - |
