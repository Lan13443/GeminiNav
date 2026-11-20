package com.wa2000.gemininav;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

import java.lang.reflect.Method;

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

    private static void injectAssist() {
        if (!acquire()) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.app.Instrumentation inst = new android.app.Instrumentation();
                    inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ASSIST);
                    XposedBridge.log(TAG + ": injected via Instrumentation");
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
                injectAssist();
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
                        injectAssist();
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": hook err " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": xiaoai hook installed");
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