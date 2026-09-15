# 笔顺 (HanziStroke) · v1.0.3-alpha

<p align="center">
  <img src="ic_launcher.png" width="100" height="100" alt="笔顺 Logo" style="border-radius: 20px; box-shadow: 0 4px 16px rgba(0,0,0,0.12);" />
</p>

<p align="center">
  <strong>专为儿童汉字学习打造的高效笔顺分解与书写动画工具</strong>
</p>

<p align="center">
  <a href="https://github.com/Hanice404/HanziStroke/releases/tag/v1.0.3-alpha">
    <img src="https://img.shields.io/badge/Release-v1.0.3--alpha-blue.svg?style=flat-square" alt="Version">
  </a>
  <img src="https://img.shields.io/badge/Platform-Android-green.svg?style=flat-square" alt="Platform">
  <img src="https://img.shields.io/badge/LLM-DeepSeek%20V4-orange.svg?style=flat-square" alt="LLM">
  <img src="https://img.shields.io/badge/ASR-Tencent%20ASR-blue.svg?style=flat-square" alt="ASR">
  <img src="https://img.shields.io/badge/TTS-Microsoft%20TTS-purple.svg?style=flat-square" alt="TTS">
</p>

---

## 📖 产品简介

「笔顺」是一款专为儿童汉字学习打造的高效笔顺分解与书写动画工具。支持语音快速提问识别、LLM 大模型智能语义提炼目标汉字，并提供微软高拟真发音教学与逐笔动画分解。

孩子们只需按住麦克风说出“故宫怎么写？”、“冰淇淋怎么写？”，应用即可通过语义理解模型自动提炼出核心汉字，并在米字格中展示规范笔顺书写动画、字词读音与逐笔分解。

## ✨ 核心特性

- ✍️ **动态笔顺分解与动画书写**：基于 HanziWriter 深度定制，支持标准田字格/米字格规范展示、自动书写动画播放与单字逐笔拆解。
- 🎙️ **智能语音提问识别 (ASR)**：毫秒级语音转文字，支持腾讯云 ASR 与 OpenAI Whisper / SiliconFlow 兼容接口。
- 🧠 **大模型智能语义提炼 (LLM)**：搭载 DeepSeek V4 语义理解模型，无论孩子说“请问森林的森怎么写呀”还是“冰淇淋”，都能精准提炼目标汉字。
- 🔊 **真人拟真发音教学 (TTS)**：内置微软 Edge 神经网络超自然拟真发音（晓晓、云希、云健等），支持多发音人自由切换与慢速/标准语速调节。
- 🛡️ **管理员配置安全保护**：内置密码保护机制（默认密码 `1221`），防止儿童误触或随意篡改 API 接口密钥。
- 📱 **多端与双重交互适配**：
  - 纯原生 Android App 封装，深度集成原生麦克风录音、TTS 桥接与物理/手势返回键拦截体验。
  - 支持手机竖屏与平板横屏自适应排版。

## ⚙️ 驱动引擎

- **语义大模型**：DeepSeek V4
- **语音识别模型**：Tencent ASR
- **真人发音引擎**：Microsoft TTS

## 🚀 下载与安装

请前往 [Releases 页面](https://github.com/Hanice404/HanziStroke/releases/tag/v1.0.3-alpha) 下载最新版本的 APK 安装包：

- **[笔顺_v1.0.3-alpha.apk (HanziStroke_v1.0.3-alpha.apk)](https://github.com/Hanice404/HanziStroke/releases/download/v1.0.3-alpha/HanziStroke_v1.0.3-alpha.apk)**

## 🛠️ 项目构建

### 环境要求
- Android Studio Ladybug / Koala 或更高版本
- JDK 17
- Android SDK (Compile SDK 34, Min SDK 24)

### 构建命令
```bash
# 编译 Debug APK
./gradlew assembleDebug

# 编译 Release APK
./gradlew assembleRelease
```

---

## 👨‍👧‍👦 关于作者

**Designed and developed by Harper & Harper's dad.**  
© 2026 Harper. All rights reserved.
