package top.niunaijun.blackbox.fake.service;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import black.android.app.BRAppOpsManager;
import black.android.os.BRServiceManager;
import black.com.android.internal.app.BRIAppOpsServiceStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;


public class IAppOpsManagerProxy extends BinderInvocationStub {
    public IAppOpsManagerProxy() {
        super(BRServiceManager.get().getService(Context.APP_OPS_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder call = BRServiceManager.get().getService(Context.APP_OPS_SERVICE);
        return BRIAppOpsServiceStub.get().asInterface(call);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        if (BRAppOpsManager.get(null)._check_mService() != null) {
            AppOpsManager appOpsManager = (AppOpsManager) BlackBoxCore.getContext().getSystemService(Context.APP_OPS_SERVICE);
            try {
                BRAppOpsManager.get(appOpsManager)._set_mService(getProxyInvocation());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        replaceSystemService(Context.APP_OPS_SERVICE);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        Class<?> returnType = method.getReturnType();

        if (isIntReturn(returnType) &&
            (methodName.startsWith("check") ||
             methodName.startsWith("note") ||
             methodName.startsWith("start"))) {
            Slog.d(TAG, "AppOps invoke: Bypassing system for " + methodName + ", allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }

        if (isSyncNotedAppOpReturn(returnType)) {
            return invokeSyncNotedAppOp(method, args);
        }

        if (methodName.startsWith("finish")) {
            Slog.d(TAG, "AppOps invoke: Bypassing system for " + methodName);
            return null;
        }

        try {
            MethodParameterUtils.replaceFirstAppPkg(args);
            MethodParameterUtils.replaceLastUid(args);
            return super.invoke(proxy, method, args);
        } catch (SecurityException e) {
            Slog.w(TAG, "AppOps invoke: SecurityException caught for " + methodName + ", allowing operation", e);
            return defaultValue(returnType);
        } catch (Throwable e) {
            Slog.e(TAG, "AppOps invoke: Error in method " + methodName, e);
            return defaultValue(returnType);
        }
    }

    private static boolean isIntReturn(Class<?> returnType) {
        return returnType == int.class || returnType == Integer.class;
    }

    private static boolean isSyncNotedAppOpReturn(Class<?> returnType) {
        return returnType != null && "android.app.SyncNotedAppOp".equals(returnType.getName());
    }

    private Object invokeSyncNotedAppOp(Method method, Object[] args) {
        try {
            MethodParameterUtils.replaceFirstAppPkg(args);
            MethodParameterUtils.replaceLastUid(args);
            Object result = method.invoke(getBase(), args);
            if (method.getReturnType().isInstance(result)) {
                return result;
            }
        } catch (Throwable e) {
            Slog.w(TAG, "AppOps invoke: SyncNotedAppOp delegation failed for " + method.getName(), e);
        }
        return allowedSyncNotedAppOp(args);
    }

    private static Object allowedSyncNotedAppOp(Object[] args) {
        try {
            Class<?> clazz = Class.forName("android.app.SyncNotedAppOp");
            int opCode = 0;
            String attributionTag = null;
            int stringIndex = 0;
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof Integer && opCode == 0) {
                        opCode = (Integer) arg;
                    }
                    if (arg instanceof String) {
                        stringIndex++;
                        if (stringIndex >= 2) {
                            attributionTag = (String) arg;
                            break;
                        }
                    }
                }
            }
            try {
                Constructor<?> constructor = clazz.getDeclaredConstructor(int.class, int.class, String.class, String.class);
                constructor.setAccessible(true);
                return constructor.newInstance(AppOpsManager.MODE_ALLOWED, opCode, attributionTag, BlackBoxCore.getHostPkg());
            } catch (Throwable ignored) {
                return clazz.getConstructor(int.class, String.class).newInstance(opCode, attributionTag);
            }
        } catch (Throwable e) {
            Slog.e(TAG, "AppOps invoke: Unable to create SyncNotedAppOp", e);
            return null;
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (isIntReturn(returnType)) {
            return AppOpsManager.MODE_ALLOWED;
        }
        if (returnType == boolean.class || returnType == Boolean.class) {
            return false;
        }
        if (returnType == long.class || returnType == Long.class) {
            return 0L;
        }
        if (returnType == float.class || returnType == Float.class) {
            return 0f;
        }
        if (returnType == double.class || returnType == Double.class) {
            return 0d;
        }
        if (returnType == short.class || returnType == Short.class) {
            return (short) 0;
        }
        if (returnType == byte.class || returnType == Byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class || returnType == Character.class) {
            return (char) 0;
        }
        return null;
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("noteProxyOperation")
    public static class NoteProxyOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("checkPackage")
    public static class CheckPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("checkOperation")
    public static class CheckOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            
            Slog.d(TAG, "AppOps CheckOperation: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    
    @ProxyMethod("checkOperationForDevice")
    public static class CheckOperationForDevice extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps CheckOperationForDevice: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("noteOperation")
    public static class NoteOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps NoteOperation: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("checkOpNoThrow")
    public static class CheckOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps CheckOpNoThrow: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    
    @ProxyMethod("startOp")
    public static class StartOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps StartOp: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("startOpNoThrow")
    public static class StartOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps StartOpNoThrow: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    
    @ProxyMethod("finishOp")
    public static class FinishOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                int op = (int) args[0];
                String name = getOpPublicName(op);
                if (name != null && isMediaStorageOrAudioOp(name)) {
                    Slog.d(TAG, "AppOps FinishOp: Finishing operation: " + name);
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    
    @ProxyMethod("noteOp")
    public static class NoteOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                int op = (int) args[0];
                String name = getOpPublicName(op);
                if (name != null && (name.contains("RECORD_AUDIO") || name.contains("AUDIO") || name.contains("MICROPHONE"))) {
                    Slog.d(TAG, "AppOps NoteOp: Allowing RECORD_AUDIO operation: " + name);
                    return AppOpsManager.MODE_ALLOWED;
                }
            } catch (Throwable ignored) {
            }
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("noteOpNoThrow")
    public static class NoteOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                int op = (int) args[0];
                String name = getOpPublicName(op);
                if (name != null && (name.contains("RECORD_AUDIO") || name.contains("AUDIO") || name.contains("MICROPHONE"))) {
                    Slog.d(TAG, "AppOps NoteOpNoThrow: Allowing RECORD_AUDIO operation: " + name);
                    return AppOpsManager.MODE_ALLOWED;
                }
            } catch (Throwable ignored) {
            }
            return method.invoke(who, args);
        }
    }

    private static boolean isMediaStorageOrAudioOp(String opPublicNameOrStr) {
        if (opPublicNameOrStr == null) return false;
        
        String n = opPublicNameOrStr.toUpperCase();
        return n.contains("READ_MEDIA")
                || n.contains("READ_EXTERNAL_STORAGE")
                || n.contains("RECORD_AUDIO")
                || n.contains("CAPTURE_AUDIO_OUTPUT")
                || n.contains("MODIFY_AUDIO_SETTINGS")
                || n.contains("AUDIO")
                || n.contains("MICROPHONE")
                || n.contains("FOREGROUND_SERVICE")
                || n.contains("SYSTEM_ALERT_WINDOW")
                || n.contains("WRITE_SETTINGS")
                || n.contains("ACCESS_FINE_LOCATION")
                || n.contains("ACCESS_COARSE_LOCATION")
                || n.contains("CAMERA")
                || n.contains("BODY_SENSORS")
                || n.contains("BLUETOOTH_SCAN")
                || n.contains("BLUETOOTH_CONNECT")
                || n.contains("BLUETOOTH_ADVERTISE")
                || n.contains("NEARBY_WIFI_DEVICES")
                || n.contains("POST_NOTIFICATIONS");
    }

    private static String getOpPublicName(int op) {
        try {
            
            java.lang.reflect.Method m = AppOpsManager.class.getMethod("opToPublicName", int.class);
            Object name = m.invoke(null, op);
            return name != null ? name.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
