# ZLink Remote

一个独立、非官方的 Android 客户端，把 ZCode 桌面端生成的 Mobile Remote Control 网页放进专用 APK。

它不是通用浏览器，也没有复刻 ZCode 的私有 relay / IPC 协议。应用只加载用户自己从桌面端拿到的官方 Remote 链接。

## 已实现

- 扫描电脑屏幕上的二维码，或从系统图片选择器读取二维码；识别完全在本机完成
- 粘贴或通过 Android 分享菜单导入 Remote 链接
- 全时深色的 Material 3 连接页；链接、连接和二维码入口收进一条紧凑操作栏
- 支持官方 `/remote/v3` 与 `/remote/v4` 链接
- 单一活动 WebView，符合官方“一次一个手机页面”的限制
- 最近 6 条连接；完整凭据使用 Android Keystore + AES-GCM 加密
- 设置里可指定启动连接；未指定时从桌面冷启动自动打开上一次连接
- 静态资源按服务器缓存规则复用，避免每次连接都冷启动
- 网络中断后指数退避自动重连，网络恢复时立即重试；WebView 渲染器异常也会自动恢复
- 任务从运行中变为完成时发送本机通知（Android 13+ 首次需授权）
- Remote 页面只显示全屏网页，不保留顶部栏、加载线或其他原生遮挡；直接使用系统侧滑返回退出
- 受约束的系统文件选择器，只接收用户明确选择的可读 `content://` URI，不申请存储权限
- Android 8.0（API 26）及以上；面向 Android 16（API 36）
- CameraX + ZXing 扫码，不依赖 Google Play Services

## 使用

1. 在桌面端 ZCode 点击左下角手机图标。
2. 在 Mobile Remote Control 弹窗中生成二维码。
3. 打开 ZLink Remote，点连接条最右侧的相机图标，选择现场扫描或从图片读取；也可以点左侧图标粘贴链接。
4. 检查应用只显示设备名和官方 `/remote/v3` 或 `/v4` 路径，再点箭头连接。

Remote 链接本身就是临时授权钥匙。手机页面关闭并不会停止电脑端 Remote；需要撤销时，请在电脑端点 **Stop**，或刷新二维码使旧链接立即失效。

## 安全边界

- 初始链接必须是 `https://zcode.z.ai/remote/v3|v4`，且带完整的 `sid`、`hash`、`t` 参数。
- 禁止明文 HTTP、非默认端口、仿冒域名、Mixed Content 与证书错误。
- 不注册 `addJavascriptInterface`，不自动批准 ZCode 的权限确认。仅在可信 v4 页面内轮询固定控件的运行/空闲状态；不读取消息文字、输入内容或任务结果。
- 顶层页面只允许停留在精确的 `zcode.z.ai` 来源；GET、POST 与重定向都会复核，用户点击的站外 HTTPS 链接交给系统浏览器。
- 完整 URL 不写入日志、页面标题、最近连接 UI、备份或 GitHub Actions 输出。
- 允许用户按需截图；截图和最近任务缩略图可能包含 Remote 凭据或会话内容，请勿公开分享。输入框和 WebView 仍不保存视图状态。
- 应用没有自建服务器，也没有统计 SDK。

更多说明见 [SECURITY.md](SECURITY.md) 和 [设计文档](docs/DESIGN.md)。

## 构建

需要 JDK 17 和 Android SDK 36；仓库自带 Gradle 8.13 Wrapper：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleStable
```

本机调试 APK 与待签名 stable APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/stable/app-stable-unsigned.apk
```

GitHub Actions 会校验 Gradle Wrapper、运行测试与 Lint，并生成临时 debug 包和未签名 stable 包。固定签名任务只在可信的 `main` 推送或手动触发时运行，私钥不进入 PR 构建；证书指纹和恢复步骤见 [docs/SIGNING.md](docs/SIGNING.md)。

## Clean-room 说明

本项目只把其他公开项目当作需求和风险资料。没有复制它们的 Kotlin、Dart、JavaScript、XML、图标、协议实现或界面。完成状态探针来自对用户授权的官方页面进行只读运行时观察；其余部分使用标准 Android 平台 API、AndroidX、CameraX 和 ZXing 从零实现。

## 许可证与品牌

源码使用 [Apache License 2.0](LICENSE)。ZCode、Z.ai 及其相关标识归各自权利人所有；本项目与其没有隶属或背书关系，也不包含官方 logo。
