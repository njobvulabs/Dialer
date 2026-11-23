package top.niunaijun.blackbox.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.R;
import top.niunaijun.blackbox.utils.Slog;
import android.util.Log;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.animation.OvershootInterpolator;


public class LauncherActivity extends Activity {
    public static final String TAG = "SplashScreen";

    public static final String KEY_INTENT = "launch_intent";
    public static final String KEY_PKG = "launch_pkg";
    public static final String KEY_USER_ID = "launch_user_id";
    private boolean isRunning = false;
    private boolean mLaunchObserved = false;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public static void launch(Intent intent, int userId) {
        try {
            Intent splash = new Intent();
            splash.setClass(BlackBoxCore.getContext(), LauncherActivity.class);
            
            splash.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            splash.putExtra(LauncherActivity.KEY_INTENT, intent);
            String launchPkg = intent.getPackage();
            if (launchPkg == null && intent.getComponent() != null) {
                launchPkg = intent.getComponent().getPackageName();
            }
            splash.putExtra(LauncherActivity.KEY_PKG, launchPkg);
            splash.putExtra(LauncherActivity.KEY_USER_ID, userId);
            BlackBoxCore.getContext().startActivity(splash);
            Slog.d(TAG, "LauncherActivity.launch() called for package: " + launchPkg);
        } catch (Exception e) {
            Slog.e(TAG, "Error in LauncherActivity.launch()", e);
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
            
            Intent intent = getIntent();
            if (intent == null) {
                Slog.w(TAG, "Intent is null, finishing activity");
                finish();
                return;
            }
            
            Intent launchIntent = intent.getParcelableExtra(KEY_INTENT);
            String packageName = intent.getStringExtra(KEY_PKG);
            int userId = intent.getIntExtra(KEY_USER_ID, 0);

            // Some launch intents are built with setClassName() only, which does not populate
            // getPackage(). Recover the package from the explicit component as a fallback.
            if (packageName == null && launchIntent != null
                    && launchIntent.getComponent() != null) {
                packageName = launchIntent.getComponent().getPackageName();
            }

            if (launchIntent == null || packageName == null) {
                Slog.w(TAG, "Missing launch intent or package name, finishing activity");
                finish();
                return;
            }

            Slog.d(TAG, "LauncherActivity.onCreate() for package: " + packageName + ", userId: " + userId);

            
            PackageInfo packageInfo = getPackageInfoWithFallback(packageName, userId);
            
            if (packageInfo == null) {
                Slog.w(TAG, "Package info not available for " + packageName + ", but proceeding with launch");
                
            } else {
                Slog.d(TAG, "Successfully retrieved package info for " + packageName);
            }
            
            
            Drawable drawable = null;
            String appName = packageName;
            try {
                if (packageInfo != null && packageInfo.applicationInfo != null) {
                    PackageManager pm = getPackageManager();
                    drawable = pm.getApplicationIcon(packageInfo.applicationInfo);
                    CharSequence label = pm.getApplicationLabel(packageInfo.applicationInfo);
                    if (label != null) appName = label.toString();
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to load app icon or name for " + packageName + ": " + e.getMessage());
            }
            setContentView(R.layout.activity_launcher);
            ImageView iconView = findViewById(R.id.iv_icon);
            TextView nameView = findViewById(R.id.tv_app_name);
            if (nameView != null) {
                nameView.setText(appName);
                nameView.setAlpha(0f);
                nameView.animate()
                    .alpha(1f)
                    .setDuration(500)
                    .setStartDelay(200)
                    .start();
            }
            if (iconView != null && drawable != null) {
                iconView.setImageDrawable(drawable);
                iconView.setScaleX(0.7f);
                iconView.setScaleY(0.7f);
                iconView.setAlpha(0f);
                iconView.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .alpha(1f)
                    .setDuration(350)
                    .setInterpolator(new OvershootInterpolator())
                    .withEndAction(() -> iconView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start())
                    .start();
            }
            
            
            // Advisory pre-flight over Binder (the :black service process). The local
            // BPackageManagerService singleton in THIS process never runs systemReady(), so
            // consulting it yields a false "no route"; that mistake must never block a launch.
            try {
                ResolveInfo resolved = BlackBoxCore.getBPackageManager().resolveActivity(
                        launchIntent, PackageManager.GET_ACTIVITIES, null, userId);
                if (resolved != null && resolved.activityInfo != null) {
                    BlackBoxCore.recordResolveCheck("OK " + resolved.activityInfo.name);
                    BlackBoxCore.recordResolvedActivity(
                            resolved.activityInfo.packageName + "/" + resolved.activityInfo.name);
                } else {
                    BlackBoxCore.recordResolveCheck("NULL (advisory, launch still attempted)");
                }
            } catch (Throwable t) {
                BlackBoxCore.recordResolveCheck("EXCEPTION " + t);
            }
            BlackBoxCore.recordLaunchStage("launch_requested");
            launchAppAsync(launchIntent, userId, packageName);
            // Watchdog: a successful route can still end in a black window if the stub process
            // never mounts the target app. Record that state so the diagnostic report explains
            // it instead of leaving the user with an unexplained black screen.
            final String targetPkg = packageName;
            mHandler.postDelayed(() -> {
                if (!mLaunchObserved && !isFinishing() && !isDestroyed()) {
                    BlackBoxCore.recordLaunchStage("no_activity_window_8s");
                    showLaunchError(targetPkg, userId,
                            "启动后 8 秒仍未出现应用窗口");
                }
            }, 8000);
            
        } catch (Exception e) {
            Slog.e(TAG, "Critical error in LauncherActivity.onCreate()", e);
            finish();
        }
    }

    
    private PackageInfo getPackageInfoWithFallback(String packageName, int userId) {
        try {
            
            return BlackBoxCore.getBPackageManager().getPackageInfo(packageName, 0, userId);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get package info for " + packageName + " (attempt 1): " + e.getMessage());
            
            try {
                
                return BlackBoxCore.getBPackageManager().getPackageInfo(packageName, 
                    android.content.pm.PackageManager.GET_META_DATA, userId);
            } catch (Exception e2) {
                Slog.w(TAG, "Failed to get package info for " + packageName + " (attempt 2): " + e2.getMessage());
                
                try {
                    
                    android.content.pm.ApplicationInfo appInfo = BlackBoxCore.getBPackageManager()
                        .getApplicationInfo(packageName, 0, userId);
                    
                    if (appInfo != null) {
                        
                        PackageInfo fallbackInfo = new PackageInfo();
                        fallbackInfo.packageName = packageName;
                        fallbackInfo.applicationInfo = appInfo;
                        fallbackInfo.versionCode = 1;
                        fallbackInfo.versionName = "1.0";
                        fallbackInfo.firstInstallTime = System.currentTimeMillis();
                        fallbackInfo.lastUpdateTime = System.currentTimeMillis();
                        
                        Slog.d(TAG, "Created fallback PackageInfo for " + packageName);
                        return fallbackInfo;
                    }
                } catch (Exception e3) {
                    Slog.w(TAG, "Failed to get application info for " + packageName + ": " + e3.getMessage());
                }
            }
        }
        
        return null;
    }

    
    private void launchAppAsync(final Intent launchIntent, final int userId,
                                final String packageName) {
        new Thread(() -> {
            try {
                Slog.d(TAG, "Starting app launch in background thread");
                // No artificial delay: the old 100ms sleep was a splash workaround that added
                // a fixed latency to every single virtual app launch.
                BlackBoxCore.recordLaunchStage("stub_start");
                BlackBoxCore.getBActivityManager().startActivity(launchIntent, userId);
                BlackBoxCore.recordLaunchStage("stub_started");
                Slog.d(TAG, "App launch initiated successfully");
            } catch (Throwable e) {
                Slog.e(TAG, "Error launching app", e);
                BlackBoxCore.recordLaunchStage("stub_start_exception");
                BlackBoxCore.get().setLastLaunchError("stub_start_exception: " + e);
                runOnUiThread(() -> showLaunchError(packageName, userId,
                        "启动虚拟 Activity 时抛出异常"));
            }
        }, "AppLaunchThread").start();
    }

    /**
     * Show the reason plus the full framework diagnostic in a scrollable dialog.
     * The user has no logcat access on this device, so a silent black screen is useless:
     * anything that fails before the target Activity appears must be readable on screen.
     */
    private void showLaunchError(String packageName, int userId, String reason) {
        try {
            String report = BlackBoxCore.get().getDiagnosticReport(packageName, userId);
            ScrollView scroll = new ScrollView(this);
            TextView text = new TextView(this);
            text.setText(reason + "\n\n" + report);
            text.setTextIsSelectable(true);
            text.setTextSize(11f);
            text.setTextColor(android.graphics.Color.parseColor("#FFDDDDDD"));
            text.setBackgroundColor(android.graphics.Color.parseColor("#FF1A1A1A"));
            text.setTypeface(android.graphics.Typeface.MONOSPACE);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            text.setPadding(pad, pad / 2, pad, pad);
            scroll.addView(text);
            new AlertDialog.Builder(this)
                    .setTitle("虚拟应用启动失败")
                    .setView(scroll)
                    .setPositiveButton("返回", (dialog, which) -> finish())
                    .setNegativeButton("继续等待", null)
                    .setCancelable(true)
                    .show();
        } catch (Throwable t) {
            Slog.e(TAG, "Failed to show launch error", t);
            try {
                Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isRunning = true;
        mLaunchObserved = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isRunning) {
            finish();
        }
    }
}
