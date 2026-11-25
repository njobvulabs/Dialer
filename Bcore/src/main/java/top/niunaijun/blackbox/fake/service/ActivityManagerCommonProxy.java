package top.niunaijun.blackbox.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;

import java.io.File;
import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.provider.FileProviderHandler;
import top.niunaijun.blackbox.utils.BBDiag;
import top.niunaijun.blackbox.utils.ComponentUtils;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.StartActivityCompat;

import static android.content.pm.PackageManager.GET_META_DATA;


public class ActivityManagerCommonProxy {
    public static final String TAG = "CommonStub";
    private static final String SANDBOX_SETTINGS_ACTIVITY =
            "com.example.ui.screens.sandbox.SandboxAppSettingsActivity";
    private static final String EXTRA_VIRTUAL_PACKAGE =
            "com.example.sandbox.extra.VIRTUAL_PACKAGE";
    private static final String EXTRA_VIRTUAL_USER_ID =
            "com.example.sandbox.extra.VIRTUAL_USER_ID";
    private static final String EXTRA_SETTINGS_ACTION =
            "com.example.sandbox.extra.SETTINGS_ACTION";
    private static final String EXTRA_SETTINGS_DATA =
            "com.example.sandbox.extra.SETTINGS_DATA";

    @ProxyMethod("startActivity")
    public static class StartActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = getIntent(args);
            Slog.d(TAG, "Hook in : " + intent);
            assert intent != null;

            if (maybeRedirectAppSettings(intent)) {
                return method.invoke(who, args);
            }
            MethodParameterUtils.replaceFirstAppPkg(args);

            if (intent.getParcelableExtra("_B_|_target_") != null) {
                return method.invoke(who, args);
            }
            if (ComponentUtils.isRequestInstall(intent)) {
                File file = FileProviderHandler.convertFile(BActivityThread.getApplication(), intent.getData());
                
                
                if (file != null && file.exists()) {
                    try {
                        PackageInfo packageInfo = BlackBoxCore.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
                        if (packageInfo != null) {
                            String packageName = packageInfo.packageName;
                            String hostPackageName = BlackBoxCore.getHostPkg();
                            if (packageName.equals(hostPackageName)) {
                                Slog.w(TAG, "Blocked attempt to install BlackBox app from within BlackBox: " + packageName);
                                
                                return 0;
                            }
                        }
                    } catch (Exception e) {
                        Slog.w(TAG, "Could not verify if this is BlackBox app: " + e.getMessage());
                    }
                }
                
                if (BlackBoxCore.get().requestInstallPackage(file, BActivityThread.getUserId())) {
                    return 0;
                }
                intent.setData(FileProviderHandler.convertFileUri(BActivityThread.getApplication(), intent.getData()));
                return method.invoke(who, args);
            }
            String dataString = intent.getDataString();
            String guestPackage = BActivityThread.getAppPackageName();
            if (dataString != null && guestPackage != null
                    && dataString.equals("package:" + guestPackage)) {
                BBDiag.trace(TAG, "packageUri action=" + intent.getAction()
                        + " data=" + dataString
                        + " explicitPackage=" + intent.getPackage()
                        + " guest=" + guestPackage
                        + " -> host=" + BlackBoxCore.getHostPkg());
                intent.setData(Uri.parse("package:" + BlackBoxCore.getHostPkg()));
            }

            ResolveInfo resolveInfo = BlackBoxCore.getBPackageManager().resolveActivity(
                    intent,
                    GET_META_DATA,
                    StartActivityCompat.getResolvedType(args),
                    BActivityThread.getUserId());
            if (resolveInfo == null) {
                String origPackage = intent.getPackage();
                if (intent.getPackage() == null && intent.getComponent() == null) {
                    intent.setPackage(BActivityThread.getAppPackageName());
                } else {
                    origPackage = intent.getPackage();
                }
                resolveInfo = BlackBoxCore.getBPackageManager().resolveActivity(
                        intent,
                        GET_META_DATA,
                        StartActivityCompat.getResolvedType(args),
                        BActivityThread.getUserId());
                if (resolveInfo == null) {
                    intent.setPackage(origPackage);
                    return method.invoke(who, args);
                }
            }


            intent.setExtrasClassLoader(who.getClass().getClassLoader());
            intent.setComponent(new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name));
            BlackBoxCore.getBActivityManager().startActivityAms(BActivityThread.getUserId(),
                    StartActivityCompat.getIntent(args),
                    StartActivityCompat.getResolvedType(args),
                    StartActivityCompat.getResultTo(args),
                    StartActivityCompat.getResultWho(args),
                    StartActivityCompat.getRequestCode(args),
                    StartActivityCompat.getFlags(args),
                    StartActivityCompat.getOptions(args));
            return 0;
        }

        private static boolean maybeRedirectAppSettings(Intent intent) {
            if (intent == null) return false;
            String action = intent.getAction();
            if (action == null) return false;
            boolean knownAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(action)
                    || Settings.ACTION_APPLICATION_SETTINGS.equals(action)
                    || Settings.ACTION_APP_NOTIFICATION_SETTINGS.equals(action)
                    || Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION.equals(action)
                    || Settings.ACTION_MANAGE_WRITE_SETTINGS.equals(action)
                    || Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.equals(action)
                    || Settings.ACTION_MANAGE_OVERLAY_PERMISSION.equals(action)
                    || Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES.equals(action)
                    || Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS.equals(action);
            if (!knownAction) return false;

            String guestPackage = BActivityThread.getAppPackageName();
            if (guestPackage == null) return false;
            Uri data = intent.getData();
            String dataPackage = null;
            if (data != null && "package".equalsIgnoreCase(data.getScheme())) {
                dataPackage = data.getSchemeSpecificPart();
            }
            String extraPackage = intent.getStringExtra(Settings.EXTRA_APP_PACKAGE);
            if (extraPackage == null) {
                extraPackage = intent.getStringExtra("android.intent.extra.PACKAGE_NAME");
            }
            if (extraPackage == null) {
                extraPackage = intent.getStringExtra("android.provider.extra.PACKAGE_NAME");
            }
            if (!guestPackage.equals(dataPackage) && !guestPackage.equals(extraPackage)) {
                return false;
            }

            String originalData = data == null ? null : data.toString();
            BBDiag.trace(TAG, "appSettings action=" + action
                    + " data=" + originalData
                    + " explicitPackage=" + intent.getPackage()
                    + " extraPackage=" + extraPackage
                    + " guest=" + guestPackage
                    + " -> sandboxSettingsActivity");
            intent.setData(null);
            intent.setPackage(null);
            intent.setSelector(null);
            intent.setComponent(new ComponentName(
                    BlackBoxCore.getHostPkg(), SANDBOX_SETTINGS_ACTIVITY));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(EXTRA_VIRTUAL_PACKAGE, guestPackage);
            intent.putExtra(EXTRA_VIRTUAL_USER_ID, BActivityThread.getUserId());
            intent.putExtra(EXTRA_SETTINGS_ACTION, action);
            intent.putExtra(EXTRA_SETTINGS_DATA, originalData);
            return true;
        }

        private Intent getIntent(Object[] args) {
            int index;
            if (BuildCompat.isR()) {
                index = 3;
            } else {
                index = 2;
            }
            if (args[index] instanceof Intent) {
                return (Intent) args[index];
            }
            for (Object arg : args) {
                if (arg instanceof Intent) {
                    return (Intent) arg;
                }
            }
            return null;
        }
    }

    @ProxyMethod("startActivities")
    public static class StartActivities extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int index = getIntents();
            Intent[] intents = (Intent[]) args[index++];
            String[] resolvedTypes = (String[]) args[index++];
            IBinder resultTo = (IBinder) args[index++];
            Bundle options = (Bundle) args[index];

            for (Intent intent : intents) {
                if (StartActivity.maybeRedirectAppSettings(intent)) {
                    for (Intent redirected : intents) {
                        redirected.setExtrasClassLoader(who.getClass().getClassLoader());
                    }
                    return method.invoke(who, args);
                }
            }

            if (!ComponentUtils.isSelf(intents)) {
                return method.invoke(who, args);
            }

            for (Intent intent : intents) {
                intent.setExtrasClassLoader(who.getClass().getClassLoader());
            }
            return BlackBoxCore.getBActivityManager().startActivities(BActivityThread.getUserId(),
                    intents, resolvedTypes, resultTo, options);
        }

        public int getIntents() {
            if (BuildCompat.isR()) {
                return 3;
            }
            return 2;
        }
    }

    @ProxyMethod("startIntentSenderForResult")
    public static class StartIntentSenderForResult extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("activityResumed")
    public static class ActivityResumed extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BlackBoxCore.getBActivityManager().onActivityResumed((IBinder) args[0]);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("activityDestroyed")
    public static class ActivityDestroyed extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BlackBoxCore.getBActivityManager().onActivityDestroyed((IBinder) args[0]);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("finishActivity")
    public static class FinishActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BlackBoxCore.getBActivityManager().onFinishActivity((IBinder) args[0]);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getAppTasks")
    public static class GetAppTasks extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getCallingPackage")
    public static class getCallingPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BlackBoxCore.getBActivityManager().getCallingPackage((IBinder) args[0], BActivityThread.getUserId());
        }
    }

    @ProxyMethod("getCallingActivity")
    public static class getCallingActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BlackBoxCore.getBActivityManager().getCallingActivity((IBinder) args[0], BActivityThread.getUserId());
        }
    }
}
