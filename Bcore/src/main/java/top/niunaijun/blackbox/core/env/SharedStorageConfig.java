package top.niunaijun.blackbox.core.env;

import android.util.AtomicFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class SharedStorageConfig {
    private static final String FILE_NAME = "shared-storage.conf";
    private static final Object LOCK = new Object();
    private static final Set<String> sEnabled = new HashSet<>();

    private SharedStorageConfig() {
    }

    public static boolean has(String packageName, int userId) {
        if (packageName == null || packageName.isEmpty()) return false;
        synchronized (LOCK) {
            load();
            return sEnabled.contains(key(packageName, userId));
        }
    }

    public static void set(String packageName, int userId, boolean enabled) {
        if (packageName == null || packageName.isEmpty()) return;
        synchronized (LOCK) {
            load();
            String key = key(packageName, userId);
            if (enabled) {
                sEnabled.add(key);
            } else {
                sEnabled.remove(key);
            }
            save();
        }
    }

    private static String key(String packageName, int userId) {
        return userId + "|" + packageName;
    }

    private static File file() {
        File dir = BEnvironment.getSystemDir();
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, FILE_NAME);
    }

    private static void load() {
        sEnabled.clear();
        File f = file();
        if (!f.exists()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) sEnabled.add(trimmed);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void save() {
        AtomicFile atomic = new AtomicFile(file());
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            ArrayList<String> keys = new ArrayList<>(sEnabled);
            Collections.sort(keys);
            for (String key : keys) {
                out.write((key + "\n").getBytes("UTF-8"));
            }
            atomic.finishWrite(out);
        } catch (Throwable t) {
            if (out != null) atomic.failWrite(out);
        }
    }
}
