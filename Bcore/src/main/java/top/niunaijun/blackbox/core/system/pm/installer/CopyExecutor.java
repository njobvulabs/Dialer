package top.niunaijun.blackbox.core.system.pm.installer;


import android.text.TextUtils;

import java.io.File;
import java.io.IOException;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.NativeUtils;
import top.niunaijun.blackbox.utils.Slog;


public class CopyExecutor implements Executor {

    public static final String TAG = "CopyExecutor";

    /** Short reason of the last failure, propagated to the install toast for diagnosis. */
    public static volatile String lastError;

    private static String describe(Throwable t) {
        String message = t.getMessage();
        String text = t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        StackTraceElement[] stack = t.getStackTrace();
        if (stack != null && stack.length > 0) {
            StackTraceElement frame = stack[0];
            text += " @" + frame.getClassName() + "." + frame.getMethodName()
                    + (frame.getLineNumber() > 0 ? ":" + frame.getLineNumber() : "");
        }
        return text.length() > 220 ? text.substring(0, 220) : text;
    }

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        lastError = null;
        if (ps == null || ps.pkg == null) {
            lastError = "Null package settings";
            return -3;
        }
        if (TextUtils.isEmpty(ps.pkg.baseCodePath) || TextUtils.isEmpty(ps.pkg.packageName)) {
            if (TextUtils.isEmpty(ps.pkg.baseCodePath) && TextUtils.isEmpty(ps.pkg.packageName)) {
                lastError = "baseCodePath and packageName are null";
            } else if (TextUtils.isEmpty(ps.pkg.baseCodePath)) {
                lastError = "baseCodePath is null";
            } else {
                lastError = "packageName is null";
            }
            return -3;
        }
        try {
            if (!option.isFlag(InstallOption.FLAG_SYSTEM)) {
                try {
                    NativeUtils.copyNativeLib(new File(ps.pkg.baseCodePath),
                            BEnvironment.getAppLibDir(ps.pkg.packageName));
                } catch (Exception e) {
                    lastError = describe(e);
                    Slog.e(TAG, "copyNativeLib failed: apk=" + ps.pkg.baseCodePath
                            + ", libDir=" + BEnvironment.getAppLibDir(ps.pkg.packageName), e);
                    return -3;
                }
            }
        } catch (Exception e) {
            lastError = describe(e);
            Slog.e(TAG, "copy step failed", e);
            return -1;
        }
        if (option.isFlag(InstallOption.FLAG_STORAGE)) {

            File origFile = new File(ps.pkg.baseCodePath);
            File newFile = BEnvironment.getBaseApkDir(ps.pkg.packageName);
            try {
                if (option.isFlag(InstallOption.FLAG_URI_FILE)) {
                    boolean b = FileUtils.renameTo(origFile, newFile);
                    if (!b) {
                        FileUtils.copyFile(origFile, newFile);
                    }
                } else {
                    FileUtils.copyFile(origFile, newFile);
                }
                newFile.setReadOnly();

                ps.pkg.baseCodePath = newFile.getAbsolutePath();
            } catch (IOException e) {
                lastError = describe(e);
                Slog.e(TAG, "copy apk failed: " + origFile + " -> " + newFile, e);
                return -4;
            }
        } else if (option.isFlag(InstallOption.FLAG_SYSTEM)) {

        }
        return 0;
    }
}
