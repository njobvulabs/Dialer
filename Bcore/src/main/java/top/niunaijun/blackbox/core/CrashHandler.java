package top.niunaijun.blackbox.core;

import top.niunaijun.blackbox.BlackBoxCore;


public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private Thread.UncaughtExceptionHandler mDefaultHandler;

    public static void create() {
        new CrashHandler();
    }

    public CrashHandler() {
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        // This handler is installed inside the virtual (stub) process. Persist the stack to
        // the shared private files dir before delegating, so the host UI can display it even
        // when logcat is unavailable.
        try {
            top.niunaijun.blackbox.utils.BBDiag.crash(t, e);
        } catch (Throwable ignored) {
        }
        try {
            if (BlackBoxCore.get().getExceptionHandler() != null) {
                BlackBoxCore.get().getExceptionHandler().uncaughtException(t, e);
            }
        } catch (Throwable ignored) {
        }
        if (mDefaultHandler != null) {
            mDefaultHandler.uncaughtException(t, e);
        }
    }
}
