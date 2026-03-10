# TableTime 📅

一个专为 **四川农业大学 (SICAU)** 打造的智能课表助手。

* **深度适配**：修复了在 Vivo/OPPO 等机型上由于 `secure_config` 硬件加密导致的登录失效问题。
* **智能登录**：集成教务处验证码识别逻辑，支持短信 MFA 验证，但需手动输入。
* ** Material Design 3**：基于最新 Android 规范设计的 UI，支持深色模式。
* **本地存储**：使用 Room/Preferences 安全加密存储课程信息。

## 🛠️ 技术栈

- **语言**: Kotlin
- **架构**: MVVM + Jetpack
- **开发工具**: Android Studio Jellyfish (2023.3.1)
- **运行环境**: 已在 RTX 4050 驱动的开发环境下通过真机调试

## 🚀 快速开始

1. **克隆项目**
   ```bash
   git clone [https://github.com/smith-hunt/TableTime.git](https://github.com/smith-hunt/TableTime.git)
