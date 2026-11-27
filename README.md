# GeminiNav

把小米 / HyperOS 导航栏「长按小白条」的行为，从小爱改成唤起 Gemini（Google 助理）。

模块作用域：`com.miui.voiceassist`、`com.miui.home`

## 原理

```
长按小白条
  → com.miui.home 硬编码启动小爱 VoiceService
     （extra: voice_assist_start_from_key=long_press_fullscreen_gesture_line）
  → 模块认出是长按，param.setResult(START_NOT_STICKY) 拦掉，小爱不启动
  → 注入 KEYCODE_ASSIST
  → 系统读 secure/voice_interaction_service
  → com.google.android.googlequicksearchbox/
     com.google.android.voiceinteraction.GsaVoiceInteractionService
  → Gemini 对话界面
```

MIUI 桌面启动小爱是**硬编码**的，既不读 `NavLongPress` 也不读 `long_press_home_key`，
所以改系统设置没用，唯一可行的入口是拦 `VoiceService.onStartCommand`。

## 关键坑：设置会被小爱抢回去

`secure/voice_interaction_service` 会在小爱启动、更新或系统调整 AI 助手时被写回：

```
com.miui.voiceassist/com.xiaomi.voiceassistant.AssistInteractionService
```

一旦被写回，`KEYCODE_ASSIST` 就原地折回小爱，表现为「长按调不出 Gemini」。

本模块在 `com.miui.voiceassist` 进程里 hook `android.provider.Settings.Secure.putString`，
当 key 是 `voice_interaction_service` 或 `assistant`、且 value 含 `com.miui.voiceassist` 时，
把参数改成 Google 的 Gsa 组件。日志会留下 `blocked rewrite of <key> -> Google`。

## 为什么不用显式 Intent 直接拉起

`com.google.android.googlequicksearchbox/.GoogleAppImplicitActionAssistGatewayInternal`
是系统解析 `ACTION_ASSIST` 的默认入口，但它依赖系统传入的 assist 参数
（`ASSIST_KEY_LAUNCHING_FROM_ASSIST_GESTURE` 等）才会进语音 / 对话模式。

用 `setComponent` 自己拉起时没有这些参数，只会打开 Google App 搜索主页，进不了 Gemini。
这条路试过，是死路。

## 手动修复

万一模块没拦住，直接把设置写回去即可，改完不需要重启：

```sh
settings put secure voice_interaction_service \
  com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService
```

## 构建

需要 `javac`、`d8`、`aapt2`、`apksigner`（Android SDK build-tools）。

```sh
bash build.sh
adb install -r GeminiNav_v1.apk
adb shell "kill -9 \$(pidof com.miui.voiceassist)"   # 重启小爱进程即可，不必重启手机
```

## 安装后

1. 在 LSPosed 里启用模块，作用域勾选 `com.miui.voiceassist` 与 `com.miui.home`
2. 重启小爱进程（或重启手机）
3. 长按小白条测试

## 日志验证

模块日志走 `XposedBridge.log`，前缀 `GeminiNav`。出现下面这组才算走对路径：

```
GeminiNav: nav long press intercepted -> Gemini
GeminiNav: assist key injected
```

## 注意

- 需要 LSPosed（或兼容的 Xposed 框架）
- 只在 HyperOS / MIUI 上验证过，AOSP 上导航栏行为不同，可能不适用
- 需要系统里装有 Google App 并把默认助理设为 Gemini
