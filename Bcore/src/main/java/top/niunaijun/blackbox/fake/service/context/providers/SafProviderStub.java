package top.niunaijun.blackbox.fake.service.context.providers;

import android.os.IInterface;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.utils.AttributionSourceUtils;

public class SafProviderStub extends ClassInvocationStub implements BContentProvider {
    public static final String AUTHORITY = "com.android.externalstorage.documents";

    private IInterface mBase;

    @Override
    public IInterface wrapper(IInterface contentProviderProxy, String appPkg) {
        mBase = contentProviderProxy;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
    }

    @Override
    protected void onBindMethod() {
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) {
            return method.invoke(mBase, args);
        }
        AttributionSourceUtils.fixAttributionSourceInArgs(args);
        fixCallingPackage(args);
        return method.invoke(mBase, args);
    }

    private void fixCallingPackage(Object[] args) {
        if (args == null || args.length == 0) return;
        String guestPkg = BActivityThread.getAppPackageName();
        if (guestPkg != null && guestPkg.equals(args[0])) {
            args[0] = BlackBoxCore.getHostPkg();
        }
    }
}
