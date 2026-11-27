package com.wa2000.gemininav;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "GeminiNav";
    private static final String PKG = "com.miui.voiceassist";
    private static final String HOME_PKG = "com.miui.home";
    private static final String FROM_KEY = "voice_assist_start_from_key";
    private static final String NAV_LONG_PRESS = "long_press_fullscreen_gesture_line";

    /** 系统助理设置的两个键，小爱会偷偷把自己写回去。 */
    private static final String KEY_VIA = "voice_interaction_service";
    private static final String KEY_ASSISTANT = "assistant";
    private static final String GSA_VIA =
            "com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService";
    private static final String XIAOAI = "com.miui.voiceassist";

    private static final Object LOCK = new Object();
    private static long lastInject = 0L;

    private static boolean acquire() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now - lastInject < 1500L) {
                return false;
            }
            lastInject = now;
            return true;
        }
    }

    private static boolean isNavLongPress(Intent it) {
        if (it == null) {
            return false;
        }
        ComponentName cn = it.getComponent();
        if (cn == null || !PKG.equals(cn.getPackageName())) {
            return false;
        }
        String c = cn.getClassName();
        if (c == null || !c.endsWith("VoiceService")) {
            return false;
        }
        Bundle b = it.getExtras();
        if (b == null) {
            return false;
        }
        return NAV_LONG_PRESS.equals(b.getString(FROM_KEY));
    }

    /**
     * 走系统助理按键。系统会读 secure/voice_interaction_service，
     * 指向 GsaVoiceInteractionService 时直接进 Gemini 对话界面。
     */
    private static void injectAssist() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.app.Instrumentation inst = new android.app.Instrumentation();
                    inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ASSIST);
                    XposedBridge.log(TAG + ": assist key injected");
                    return;
                } catch (Throwable t2) {
                    XposedBridge.log(TAG + ": inst err " + t2);
                }
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "input keyevent 219"});
                    int code = p.waitFor();
                    XposedBridge.log(TAG + ": su exit " + code);
                } catch (Throwable t3) {
                    XposedBridge.log(TAG + ": su err " + t3);
                }
            }
        }, "GeminiNav-inject");
        t.start();
    }

    private static void trigger(Context ctx) {
        if (!acquire()) {
            return;
        }
        injectAssist();
    }

    /**
     * 防抢：小爱把 voice_interaction_service / assistant 写回自己时，当场改成 Google。
     * 这是"长按又调不出 Gemini"的根因，hook 住就一劳永逸。
     */
    private static void hookSettingsGuard() {
        try {
            XposedBridge.hookAllMethods(android.provider.Settings.Secure.class, "putString",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args == null || param.args.length < 3) {
                                    return;
                                }
                                Object name = param.args[1];
                                Object value = param.args[2];
                                if (!(name instanceof String) || !(value instanceof String)) {
                                    return;
                                }
                                String k = (String) name;
                                String v = (String) value;
                                if ((KEY_VIA.equals(k) || KEY_ASSISTANT.equals(k))
                                        && v.contains(XIAOAI)) {
                                    param.args[2] = GSA_VIA;
                                    XposedBridge.log(TAG + ": blocked rewrite of " + k + " -> Google");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": guard err " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": settings guard installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": guard hook err " + t);
        }
    }

    private static final XC_MethodHook HOME_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Intent it = null;
                Object[] args = param.args;
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof Intent) {
                        it = (Intent) args[i];
                        break;
                    }
                }
                if (!isNavLongPress(it)) {
                    return;
                }
                param.setResult(null);
                XposedBridge.log(TAG + ": home intercepted -> Gemini");
                trigger(null);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": home hook err " + t);
            }
        }
    };

    private static void hookHome(XC_LoadPackage.LoadPackageParam lp) {
        XposedBridge.log(TAG + ": loaded into home");
        try {
            Class<?> ctxImpl = Class.forName("android.app.ContextImpl", false, lp.classLoader);
            XposedBridge.hookAllMethods(ctxImpl, "startServiceCommon", HOME_HOOK);
            XposedBridge.log(TAG + ": home hook startServiceCommon ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": home hook err " + t);
        }
    }

    private static void hookXiaoai(XC_LoadPackage.LoadPackageParam lp) {
        XposedBridge.log(TAG + ": loaded into " + lp.packageName);
        try {
            Class<?> svc = null;
            try {
                svc = Class.forName("com.xiaomi.voiceassistant.VoiceService", false, lp.classLoader);
            } catch (Throwable ignored) {
            }
            if (svc == null) {
                XposedBridge.log(TAG + ": VoiceService not found");
                return;
            }
            XposedBridge.hookAllMethods(svc, "onStartCommand", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof Service)) {
                            return;
                        }
                        Intent it = null;
                        Object[] args = param.args;
                        for (int i = 0; i < args.length; i++) {
                            if (args[i] instanceof Intent) {
                                it = (Intent) args[i];
                                break;
                            }
                        }
                        if (!isNavLongPress(it)) {
                            return;
                        }
                        param.setResult(Service.START_NOT_STICKY);
                        XposedBridge.log(TAG + ": nav long press intercepted -> Gemini");
                        trigger((Service) param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": hook err " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": xiaoai hook installed");
            hookSettingsGuard();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": init err " + t);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (PKG.equals(lp.packageName)) {
            hookXiaoai(lp);
        } else if (HOME_PKG.equals(lp.packageName)) {
            hookHome(lp);
        }
    }
}