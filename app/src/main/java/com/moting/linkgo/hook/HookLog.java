package com.moting.linkgo.hook;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

final class HookLog {
    private HookLog() {
    }

    static void d(XposedInterface xposed, String tag, String message) {
        Log.println(Log.DEBUG, tag, message);
        xposed.log(Log.DEBUG, tag, message);
    }

    static void w(XposedInterface xposed, String tag, String message, Throwable throwable) {
        Log.println(Log.WARN, tag, message + "\n" + Log.getStackTraceString(throwable));
        xposed.log(Log.WARN, tag, message, throwable);
    }

    static void e(XposedInterface xposed, String tag, String message, Throwable throwable) {
        Log.println(Log.ERROR, tag, message + "\n" + Log.getStackTraceString(throwable));
        xposed.log(Log.ERROR, tag, message, throwable);
    }
}