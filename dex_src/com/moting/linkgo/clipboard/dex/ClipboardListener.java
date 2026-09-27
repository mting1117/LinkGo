package com.moting.linkgo.clipboard.dex;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.IClipboard;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.Arrays;

public class ClipboardListener {

    // args[0] is host process id
    public static void main(String[] args) {
        System.out.println(new Event(EventEnum.comment, "start args = " + Arrays.toString(args)));
        boolean registered = false;
        OnPrimaryClipChangedListenerAdapter adapter = null;
        Looper mainLooper = null;
        Thread monitorThread = null;
        try {
            Looper.prepare();
            mainLooper = Looper.myLooper();
            System.out.println(new Event(EventEnum.comment, "uid = " + Os.getuid()));
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            System.out.println(new Event(EventEnum.comment, "activityThread = " + activityThread));
            var systemMain = activityThread.getMethod("systemMain").invoke(null);
            System.out.println(new Event(EventEnum.comment, "systemMain = " + systemMain));
            var method = activityThread.getMethod("getSystemContext");
            Context context = (Context) method.invoke(systemMain);
            System.out.println(new Event(EventEnum.comment, "getSystemContext = " + context));
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            System.out.println(new Event(EventEnum.comment, "ClipboardManager = " + cm));
            Field mServiceField = ClipboardManager.class.getDeclaredField("mService");
            System.out.println(new Event(EventEnum.comment, "mServiceField = " + mServiceField));
            mServiceField.setAccessible(true);
            var clipboard = (IClipboard) mServiceField.get(cm);
            System.out.println(new Event(EventEnum.comment, "IClipboard = " + clipboard));
            adapter = new PrimaryClipChangedListenerAdapter(clipboard);
            registered = adapter.register();
            if (registered) {
                System.out.println(new Event(EventEnum.comment, "Clipboard listening started..."));
            } else {
                System.err.println(new Event(EventEnum.comment, "Clipboard listening startup failed!!"));
                System.out.println(new Event(EventEnum.eof, "finished"));
                System.exit(1);
            }

            monitorThread = monitorExitSignal(mainLooper);

            var handler = new Handler(mainLooper);
            if (args.length > 0) {
                monitorHostProcess(Integer.parseInt(args[0]), handler, 1000);
            } else {
                System.out.println(new Event(EventEnum.comment, "No host PID provided, skipping host monitoring"));
            }

            Looper.loop();
        } catch (Exception e) {
            System.out.println(new Event(EventEnum.fatal, "exception: " + e.getMessage() + ". "));
            e.printStackTrace();
            if (mainLooper != null) {
                mainLooper.quitSafely();
            }
            if (monitorThread != null && monitorThread.isAlive()) {
                monitorThread.interrupt();
            }
        } finally {
            System.out.println(new Event(EventEnum.eof, "finished"));
            try {
                if (adapter != null && registered) {
                    adapter.remove();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static Thread monitorExitSignal(Looper looper) {
        var thread = new Thread(() -> {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
                String line;
                while ((line = br.readLine()) != null) {
                    if ("exit".equals(line)) {
                        looper.quitSafely();
                        System.out.println(new Event(EventEnum.comment, "receive exit command"));
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println(new Event(EventEnum.comment, e.getMessage()));
            }
        });
        thread.start();
        return thread;
    }

    public static boolean isProcessAlive(int pid) {
        try {
            Os.kill(pid, 0);
            return true;
        } catch (ErrnoException e) {
            // 在 Linux/Android 规范中：
            // EPERM 表示目标进程存在，但当前调用者(如 uid=2000 shell)无权向宿主发送信号
            // 仅 ESRCH 才表示进程确实不存在 (No such process)
            if (e.errno == android.system.OsConstants.EPERM) {
                return true;
            }
            try {
                return new java.io.File("/proc/" + pid).exists();
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private static void monitorHostProcess(int pid, Handler handler, long delayMillis) {
        if (!isProcessAlive(pid)) {
            System.err.println(new Event(EventEnum.comment, "host dead"));
            System.exit(0);
            return;
        }
        handler.postDelayed(() -> monitorHostProcess(pid, handler, delayMillis), delayMillis);
    }
}