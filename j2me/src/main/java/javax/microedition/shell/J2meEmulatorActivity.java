package javax.microedition.shell;

import android.content.Intent;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.LinkedHashMap;

import javax.microedition.util.ContextHolder;
import javax.microedition.util.J2meHost;

import ru.playsoftware.j2meloader.config.Config;

public class J2meEmulatorActivity extends J2meHost {
    public static final String EXTRA_JAR_PATH = "nj_jar_path";
    public static final String EXTRA_APP_ID = "nj_app_id";

    private MicroLoader microLoader;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.addView(getDisplayableContainer(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(getOverlayView(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        ContextHolder.setAppContext(getApplicationContext());
        ContextHolder.setCurrentActivity(this);

        String jarPath = getIntent().getStringExtra(EXTRA_JAR_PATH);
        String appId = getIntent().getStringExtra(EXTRA_APP_ID);
        if (jarPath == null && appId != null) {
            jarPath = new File(Config.getAppDir(), appId).getAbsolutePath();
        }
        if (jarPath == null) {
            Toast.makeText(this, "Missing J2ME app path", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        try {
            microLoader = new MicroLoader(getApplicationContext(), jarPath);
            microLoader.init();
            microLoader.applyConfiguration();
            LinkedHashMap<String, String> midlets = microLoader.loadMIDletList();
            if (midlets.isEmpty()) {
                Toast.makeText(this, "No MIDlet found", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            String main = midlets.keySet().iterator().next();
            String title = midlets.get(main);
            if (title != null) setTitle(title);
            MidletThread.create(microLoader, main);
            MidletThread.resumeApp();
        } catch (Throwable t) {
            Toast.makeText(this, String.valueOf(t.getMessage()), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setVisible(true);
        if (!isPauseSuppressed()) {
            MidletThread.resumeApp();
        }
    }

    @Override
    protected void onPause() {
        if (!isPauseSuppressed()) {
            setVisible(false);
            MidletThread.pauseApp();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ContextHolder.setCurrentActivity(null);
        microLoader = null;
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        ContextHolder.notifyOnActivityResult(requestCode, resultCode, data);
    }
}
