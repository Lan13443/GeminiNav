# GeminiNav

把 MIUI / HyperOS 全面屏手势里的 **长按底部手势条**，从「小爱识屏」改成唤起 **Google Gemini**。长按电源键的行为完全不受影响。

LSPosed 模块 · 作用域只有 `com.miui.voiceassist` · 不反编译系统 APK、不改系统设置

---

## 起因

在 HyperOS 上，长按底部手势条会调起小爱的识屏。系统里看起来有几个相关设置项：

```
secure/NavLongPress          = VoiceAssistantScreenRecognizer
system/long_press_home_key   = launch_xiaoai_screen_identification
```

但改它们**完全没有效果**。抓日志才发现，桌面根本不读这些值，整条链路是硬编码的：

```
com.miui.home  LongPressManager: is_in_nav_bar=true
  → DefaultLongPressHandler: long press triggered
  → handleLaunchXiaoAi(triggerType=NavLongPress)
  → startService com.miui.voiceassist/com.xiaomi.voiceassistant.VoiceService
      extras: voice_assist_start_from_key = long_press_fullscreen_gesture_line
```

所以唯一干净的切入点，是在小爱被拉起的那一刻把请求拦下来。

## 原理

模块只挂在小爱进程里，hook `VoiceService.onStartCommand`：

1. 检查 Intent extras 里的 `voice_assist_start_from_key` 是否等于 `long_press_fullscreen_gesture_line`
2. 是 → 返回 `START_NOT_STICKY` 阻断原流程，同时用 `Instrumentation.sendKeyDownUpSync(KEYCODE_ASSIST)` 注入一次 assist 按键，交回系统默认助理处理
3. 不是（比如长按电源键）→ 原样放行，一行都不改

Gemini 的浮窗是由 `VoiceInteractionSession` 绘制的（最终落地到 `FloatyActivity`），**没法用 `startActivity` 直接拉起**。试过 `GeminiGatewayActivity`、`GeminiSystemTrayActivity`、`AssistantHandoffActivity`，以及 `googleapp://gemini` 之类的 deeplink，全都是打不开或者只开了个 Google App 首页。所以只能走注入 assist 按键这条路。

## 环境要求

- 已 Root + LSPosed（KernelSU / Magisk 都行）
- 系统默认助理设为 Google（Gemini）
- 桌面为小米桌面 `com.miui.home`（4.x / HyperOS）
- 已在 小米 25042PN24C（玄戒 O1 / HyperOS）实测通过

## 安装

1. 安装 `GeminiNav.apk`
2. 在 LSPosed 管理器中启用模块
3. 模块作用域勾选 **小爱同学**（`com.miui.voiceassist`），**不要**勾别的
4. 保存后结束小爱进程，或直接重启手机
5. 长按底部手势条，应该弹出 Gemini 浮窗

长按电源键试一下，应该还是小爱识屏，说明没有影响到别的路径。

## 撤销

在 LSPosed 里关掉 GeminiNav，长按立刻恢复成小爱识屏。不需要改任何系统设置，因为本来就没改。

## 已知限制

- **依赖小爱进程存活**。长按的实际流程是「先把小爱拉起来 → 模块才有机会执行」，所以小爱被清掉后第一次长按大约要 2 秒（其中 1.9 秒花在小爱冷启动上）。Google 那边冷启动只要 200ms 左右，不是瓶颈。
- **依赖 extras key 与类名**。小爱或系统大版本更新如果改了 `VoiceService` 的类名、或者 `voice_assist_start_from_key` 这个键，模块就失效，表现是长按变回小爱识屏。到时候把 LSPosed 日志里带 `GeminiNav` 的行捞出来重新定位 hook 点即可。
- **只能注入 assist 按键**，所以 Gemini 能不能起来还取决于默认助理是不是 Google。如果改回小爱，这个模块就等于什么也没做。
- **屏幕文字复制**：Gemini 本身不提供「选中复制屏幕文字」这种交互（旧版 Google Assistant 有，换成 Gemini 之后没了）。需要 OCR 提取文字请用长按电源键的小爱识屏，或 Google Lens。
- 源码里还留了一段**实验性的桌面 hook**（`hookHome`，在 `com.miui.home` 里拦 `ContextImpl.startServiceCommon`，直接跳过小爱进程）。这段默认不生效——需要额外在 LSPosed 里勾选桌面作用域才会加载，而且没有做过长期验证。

## 构建

不依赖 Gradle，用 SDK 自带工具手动编：

```bash
export ANDROID_SDK_ROOT=~/Android/Sdk
export BUILD_TOOLS=34.0.0
bash build.sh
```

需要 JDK 11+、Android SDK（platforms + build-tools）、`r8.jar`、`python3`。

签名默认用 `~/.android/debug.keystore`，可以用环境变量换：

```bash
KS=/path/to/your.jks KS_PASS=xxx KEY_ALIAS=xxx KEY_PASS=xxx bash build.sh
```

产物在 `build/GeminiNav.apk`。

项目里 `src/de/robv/android/xposed/` 下是精简过的 Xposed API stub，只提供编译期符号，运行时用的是框架里真实的类。

## 免责

个人自用的折腾产物，仅作学习参考。刷机、root、模块注入都有风险，出问题自己担着。在不同机型/系统版本上表现可能完全不一样。
致谢
Operit ai
DeepSeek
