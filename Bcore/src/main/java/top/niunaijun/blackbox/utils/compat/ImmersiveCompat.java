package top.niunaijun.blackbox.utils.compat;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import top.niunaijun.blackbox.BlackBoxCore;

public final class ImmersiveCompat {
    private ImmersiveCompat() {
    }

    public static void apply(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !BlackBoxCore.get().isBlackProcess()) {
            return;
        }
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                View decorView = window.getDecorView();
                if (decorView == null) {
                    return;
                }
                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
        } catch (Throwable ignored) {
        }
    }
}
