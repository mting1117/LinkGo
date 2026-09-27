package com.moting.linkgo.clipboard.dex;

import android.content.IClipboard;
import android.content.IOnPrimaryClipChangedListener;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;


// References:
// https://cs.android.com/android-studio/platform/tools/adt/idea/+/mirror-goog-studio-main:streaming/screen-sharing-agent/app/src/main/java/com/android/tools/screensharing/ClipboardAdapter.java;l=29?q=Iclipboard&sq=&hl=zh-cn
// https://cs.android.com/android/platform/superproject/+/android-latest-release:frameworks/base/services/core/java/com/android/server/clipboard/ClipboardService.java;l=136?q=clipboardservice&hl=zh-cn
// https://cs.android.com/android/platform/superproject/+/android-latest-release:frameworks/base/core/java/android/content/ClipboardManager.java;l=40?q=clipboardmanager&hl=zh-cn
public abstract class OnPrimaryClipChangedListenerAdapter extends IOnPrimaryClipChangedListener.Stub {
    private static LocalDateTime lastTime = LocalDateTime.now();
    protected static long MIN_INTERVAL_MS = 100;
    private static Method addPrimaryClipChangedListenerMethod;
    private static int addPrimaryClipChangedListenerMethodVersion;
    private static Method removePrimaryClipChangedListenerMethod;
    private static int removePrimaryClipChangedListenerMethodVersion;
    private final IClipboard clipboard;
    private final String pkg;
    private final String tag;
    private final int userId;
    private final int devId;

    public OnPrimaryClipChangedListenerAdapter(IClipboard clipboard) throws NoSuchMethodException {
        this(clipboard, "com.android.shell", null, 0, 0);
    }

    public OnPrimaryClipChangedListenerAdapter(IClipboard clipboard, String pkg, String tag, int userId, int devId) throws NoSuchMethodException {
        super();
        this.clipboard = clipboard;
        this.pkg = pkg;
        this.tag = tag;
        this.userId = userId;
        this.devId = devId;
        var methods = clipboard.getClass().getDeclaredMethods();
        addPrimaryClipChangedListenerMethod = findMethodAndMakeAccessible(methods, "addPrimaryClipChangedListener");
        addPrimaryClipChangedListenerMethodVersion = addPrimaryClipChangedListenerMethod.getParameterCount();
        System.out.println(new Event(EventEnum.comment, "addPrimaryClipChangedListenerMethodVersion = " + addPrimaryClipChangedListenerMethodVersion));
        System.out.println(new Event(EventEnum.comment, "addPrimaryClipChangedListenerMethod = " + Arrays.toString(addPrimaryClipChangedListenerMethod.getParameters())));

        removePrimaryClipChangedListenerMethod = findMethodAndMakeAccessible(methods, "removePrimaryClipChangedListener");
        removePrimaryClipChangedListenerMethodVersion = removePrimaryClipChangedListenerMethod.getParameterCount();
        System.out.println(new Event(EventEnum.comment, "removePrimaryClipChangedListenerMethodVersion = " + removePrimaryClipChangedListenerMethodVersion));
        System.out.println(new Event(EventEnum.comment, "removePrimaryClipChangedListenerMethod = " + Arrays.toString(removePrimaryClipChangedListenerMethod.getParameters())));
    }

    private static Method findMethodAndMakeAccessible(Method[] methods, String name) throws NoSuchMethodException {
        for (Method method : methods) {
            if (method.getName().equals(name)) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(name);
    }

    public void dispatchPrimaryClipChanged() {
        var now = LocalDateTime.now();
        var offsetMs = Math.abs(Duration.between(now, lastTime).toMillis());
        if (offsetMs < MIN_INTERVAL_MS) {
            System.out.println(new Event(EventEnum.comment, "Interval less than " + MIN_INTERVAL_MS + "ms"));
            return;
        }
        lastTime = LocalDateTime.now();
        onPrimaryClipChanged();
    }

    public abstract void onPrimaryClipChanged();

    public boolean register() throws InvocationTargetException, IllegalAccessException {
        if (clipboard == null) {
            System.out.println(new Event(EventEnum.comment, "No IClipboard instance"));
            return false;
        }
        if (addPrimaryClipChangedListenerMethodVersion == 1) {
            addPrimaryClipChangedListenerMethod.invoke(clipboard, this, pkg);
        } else if (addPrimaryClipChangedListenerMethodVersion == 2) {
            addPrimaryClipChangedListenerMethod.invoke(clipboard, this, pkg);
        } else if (addPrimaryClipChangedListenerMethodVersion == 3) {
            addPrimaryClipChangedListenerMethod.invoke(clipboard, this, pkg, userId);
        } else if (addPrimaryClipChangedListenerMethodVersion == 4) {
            addPrimaryClipChangedListenerMethod.invoke(clipboard, this, pkg, tag, userId);
        } else if (addPrimaryClipChangedListenerMethodVersion == 5) {
            addPrimaryClipChangedListenerMethod.invoke(clipboard, this, pkg, tag, userId, devId);
        } else {
            var content = "NotMatched addListener method version, parameters:" + Arrays.toString(addPrimaryClipChangedListenerMethod.getParameters());
            System.out.println(new Event(EventEnum.comment, content));
            return false;
        }
        System.out.println(new Event(EventEnum.comment, "addPrimaryClipChangedListenerMethodVersion = " + addPrimaryClipChangedListenerMethodVersion));
        System.out.println(new Event(EventEnum.comment,"addPrimaryClipChangedListenerMethod = "+ Arrays.toString(removePrimaryClipChangedListenerMethod.getParameters())));
        return true;
    }

    public boolean remove() throws InvocationTargetException, IllegalAccessException {
        if (clipboard == null) {
            return false;
        }
        if (removePrimaryClipChangedListenerMethodVersion == 1) {
            removePrimaryClipChangedListenerMethod.invoke(clipboard, this);
        } else if (removePrimaryClipChangedListenerMethodVersion == 2) {
            removePrimaryClipChangedListenerMethod.invoke(clipboard, this, pkg);
        } else if (removePrimaryClipChangedListenerMethodVersion == 3) {
            removePrimaryClipChangedListenerMethod.invoke(clipboard, this, pkg, userId);
        } else if (removePrimaryClipChangedListenerMethodVersion == 4) {
            removePrimaryClipChangedListenerMethod.invoke(clipboard, this, pkg, tag, userId);
        } else if (removePrimaryClipChangedListenerMethodVersion == 5) {
            removePrimaryClipChangedListenerMethod.invoke(clipboard, this, pkg, tag, userId, devId);
        } else {
            var content = "NotMatched removeListener method version, parameters:" + Arrays.toString(removePrimaryClipChangedListenerMethod.getParameters());
            System.out.println(new Event(EventEnum.comment, content));
            return true;
        }
        return false;
    }
}