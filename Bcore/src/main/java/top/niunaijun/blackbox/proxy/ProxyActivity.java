package top.niunaijun.blackbox.proxy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.proxy.record.ProxyActivityRecord;
import top.niunaijun.blackbox.proxy.record.ProxyPendingRecord;
import top.niunaijun.blackbox.utils.BBDiag;
import top.niunaijun.blackbox.utils.Slog;


public class ProxyActivity extends Activity {
    public static final String TAG = "ProxyActivity";
    /** Guards against a forward loop when the ActivityThread hook is not active in a container. */
    private static final String EXTRA_FORWARDED = "_B_|_proxy_forwarded_";

    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            BBDiag.installCrashHandler(this);
            BBDiag.trace("Proxy", "onCreate " + getClass().getName()
                    + " component=" + (getIntent() == null ? "NULL" : getIntent().getComponent()));
            BBDiag.stage("stub:ProxyActivity.onCreate");
            Log.d(TAG, "onCreate");

            // If the ActivityThread hook were active this class would never be instantiated.
            // Reaching onCreate means it is not, so forwarding the target is only a fallback.
            try {
                HookManager.get().checkEnv(HCallbackProxy.class);
                BBDiag.trace("Proxy", "hookEnv checked");
            } catch (Throwable t) {
                BBDiag.trace("Proxy", "checkEnv failed: " + t);
            }

            ProxyActivityRecord record = getIntent() == null
                    ? null : ProxyActivityRecord.create(getIntent());
            if (record == null || record.mTarget == null) {
                BBDiag.stage("stub:target_intent_null");
                reportProxyFailure("target_intent_null", null);
                return;
            }

            Intent target = record.mTarget;
            boolean forwarded = target.getBooleanExtra(EXTRA_FORWARDED, false);
            BBDiag.trace("Proxy", "target=" + target.getComponent()
                    + " alreadyForwarded=" + forwarded);
            target.setExtrasClassLoader(BlackBoxCore.getApplication().getClassLoader());
            if (forwarded) {
                // Each forward creates another stub task (MULTIPLE_TASK | NEW_DOCUMENT),
                // piling up white windows until the process is killed. Break the loop here.
                BBDiag.stage("stub:forward_loop_guard");
                reportProxyFailure("forward_loop_guard", null);
                return;
            }
            target.putExtra(EXTRA_FORWARDED, true);

            BBDiag.stage("stub:startActivity(target)");
            try {
                startActivity(target);
                BBDiag.trace("Proxy", "startActivity(target) returned");
                BBDiag.stage("stub:started_target");
            } catch (Throwable t) {
                BBDiag.crash(Thread.currentThread(), t);
                BBDiag.stage("stub:start_activity_failed");
                reportProxyFailure("start_activity", t);
                return;
            }
            // Finish only AFTER the forward was issued. Finishing first can drop the window
            // in APK containers before the target has a chance to attach.
            finish();
        } catch (Throwable t) {
            BBDiag.crash(Thread.currentThread(), t);
            reportProxyFailure("proxy_exception", t);
        }
    }

    /**
     * This runs in a stub (:pN) process, so an in-memory diagnostic written in the host
     * process would not be visible. A Toast is the only feedback the user can screenshot.
     */
    private void reportProxyFailure(String reason, Throwable t) {
        String msg = "虚拟启动失败 [" + reason + "]" + (t == null ? "" : ": " + t);
        Log.e(TAG, msg, t);
        try {
            Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
        try {
            finish();
        } catch (Throwable ignored) {
        }
    }

    public static class P0 extends ProxyActivity {

    }

    public static class P1 extends ProxyActivity {

    }

    public static class P2 extends ProxyActivity {

    }

    public static class P3 extends ProxyActivity {

    }

    public static class P4 extends ProxyActivity {

    }

    public static class P5 extends ProxyActivity {

    }

    public static class P6 extends ProxyActivity {

    }

    public static class P7 extends ProxyActivity {

    }

    public static class P8 extends ProxyActivity {

    }

    public static class P9 extends ProxyActivity {

    }

    public static class P10 extends ProxyActivity {

    }

    public static class P11 extends ProxyActivity {

    }

    public static class P12 extends ProxyActivity {

    }

    public static class P13 extends ProxyActivity {

    }

    public static class P14 extends ProxyActivity {

    }

    public static class P15 extends ProxyActivity {

    }

    public static class P16 extends ProxyActivity {

    }

    public static class P17 extends ProxyActivity {

    }

    public static class P18 extends ProxyActivity {

    }

    public static class P19 extends ProxyActivity {

    }

    public static class P20 extends ProxyActivity {

    }

    public static class P21 extends ProxyActivity {

    }

    public static class P22 extends ProxyActivity {

    }

    public static class P23 extends ProxyActivity {

    }

    public static class P24 extends ProxyActivity {

    }

    public static class P25 extends ProxyActivity {

    }

    public static class P26 extends ProxyActivity {

    }

    public static class P27 extends ProxyActivity {

    }

    public static class P28 extends ProxyActivity {

    }

    public static class P29 extends ProxyActivity {

    }

    public static class P30 extends ProxyActivity {

    }

    public static class P31 extends ProxyActivity {

    }

    public static class P32 extends ProxyActivity {

    }

    public static class P33 extends ProxyActivity {

    }

    public static class P34 extends ProxyActivity {

    }

    public static class P35 extends ProxyActivity {

    }

    public static class P36 extends ProxyActivity {

    }

    public static class P37 extends ProxyActivity {

    }

    public static class P38 extends ProxyActivity {

    }

    public static class P39 extends ProxyActivity {

    }

    public static class P40 extends ProxyActivity {

    }

    public static class P41 extends ProxyActivity {

    }

    public static class P42 extends ProxyActivity {

    }

    public static class P43 extends ProxyActivity {

    }

    public static class P44 extends ProxyActivity {

    }

    public static class P45 extends ProxyActivity {

    }

    public static class P46 extends ProxyActivity {

    }

    public static class P47 extends ProxyActivity {

    }

    public static class P48 extends ProxyActivity {

    }

    public static class P49 extends ProxyActivity {

    }
}
