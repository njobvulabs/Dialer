package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Proxy for the API 33+ "locale" binder service ({@code ILocaleManager}).
 *
 * <p>Guest apps call {@code LocaleManager.getApplicationLocales()}; the system server then
 * verifies that the calling uid owns the requested package, or that it holds the
 * signature-level {@code READ_APP_SPECIFIC_LOCALES} permission. Inside a virtual process the
 * guest package name does not belong to our uid, so the call dies on a background thread with:</p>
 *
 * <pre>
 * SecurityException: getApplicationLocales: Neither user X nor current process has
 * android.permission.READ_APP_SPECIFIC_LOCALES.
 * </pre>
 *
 * <p>We replace the service with a binder that reports "no per-app language override" (an
 * empty LocaleList) and silently accepts writes — the only meaningful values for a package
 * the system server does not know about. Any other permission failure is downgraded to a
 * type-safe default instead of being allowed to kill the guest process. On Android 12 and
 * below {@code ILocaleManager} does not exist, so this proxy simply does not inject.</p>
 */
public class ILocaleManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ILocaleManagerProxy";
    private static final String NAME = "locale"; // Context.LOCALE_SERVICE

    public ILocaleManagerProxy() {
        super(BRServiceManager.get().getService(NAME));
    }

    @Override
    protected Object getWho() {
        try {
            Class<?> stub = Class.forName("android.app.ILocaleManager$Stub");
            Method asInterface = stub.getMethod("asInterface", IBinder.class);
            return asInterface.invoke(null, BRServiceManager.get().getService(NAME));
        } catch (Throwable t) {
            // Pre-Android 13, or hidden-API restriction: injectHook() bails out when
            // getWho() returns null, so no service is replaced and nothing else changes.
            Slog.w(TAG, "ILocaleManager unavailable, locale proxy skipped: " + t);
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(NAME);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    /** Last line of defence: a system-server permission check must never kill the guest. */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            return super.invoke(proxy, method, args);
        } catch (SecurityException e) {
            Slog.w(TAG, "suppressed SecurityException from " + method.getName() + ": " + e);
            return defaultValue(method.getReturnType());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if ("android.os.LocaleList".equals(type.getName())) {
            try {
                return Class.forName("android.os.LocaleList")
                        .getMethod("getEmptyLocaleList").invoke(null);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Follow the system language: there is no per-app locale for a virtual package. */
    @ProxyMethod("getApplicationLocales")
    public static class GetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return defaultValue(method.getReturnType());
        }
    }

    /** Per-app locales cannot be persisted by the system for a virtual package; accept quietly. */
    @ProxyMethod("setApplicationLocales")
    public static class SetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return defaultValue(method.getReturnType());
        }
    }
}
