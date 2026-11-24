package top.niunaijun.blackbox.core.env;

import android.util.AtomicFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

public final class SharedStorageConfig {
    private static final String FILE_NAME = "shared-storage.conf";
    private static final Object LOCK = new Object();
    private static final Set<String> sEnabled = new HashSet<>();

    public static final Set<String> SHARED_FOLDERS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("Download", "Documents", "DCIM", "Movies", "Music", "Pictures")));

    private SharedStorageConfig() {
    }

    public static boolean has(String packageName, int userId) {
        return !getFolders(packageName, userId).isEmpty();
    }

    public static boolean hasFolder(String packageName, int userId, String folder) {
        return folder != null && getFolders(packageName, userId).contains(folder);
    }

    public static Set<String> getFolders(String packageName, int userId) {
        if (packageName == null || packageName.isEmpty()) return Collections.emptySet();
        synchronized (LOCK) {
            load();
            LinkedHashSet<String> folders = new LinkedHashSet<>();
            String prefix = key(packageName, userId) + "|";
            for (String entry : sEnabled) {
                if (!entry.startsWith(prefix)) continue;
                String folder = entry.substring(prefix.length());
                if (SHARED_FOLDERS.contains(folder)) folders.add(folder);
            }
            if (sEnabled.contains(key(packageName, userId))) folders.addAll(SHARED_FOLDERS);
            return folders;
        }
    }

    public static void set(String packageName, int userId, boolean enabled) {
        setFolders(packageName, userId, enabled ? SHARED_FOLDERS : Collections.<String>emptySet());
    }

    public static void setFolders(String packageName, int userId, Set<String> folders) {
        if (packageName == null || packageName.isEmpty()) return;
        synchronized (LOCK) {
            load();
            String base = key(packageName, userId);
            String prefix = base + "|";
            Iterator<String> iterator = sEnabled.iterator();
            while (iterator.hasNext()) {
                String entry = iterator.next();
                if (entry.equals(base) || entry.startsWith(prefix)) iterator.remove();
            }
            if (folders != null) {
                for (String folder : folders) {
                    if (SHARED_FOLDERS.contains(folder)) sEnabled.add(key(packageName, userId, folder));
                }
            }
            save();
        }
    }

    public static void setFolder(String packageName, int userId, String folder, boolean enabled) {
        if (packageName == null || packageName.isEmpty() || folder == null || !SHARED_FOLDERS.contains(folder)) return;
        synchronized (LOCK) {
            LinkedHashSet<String> current = new LinkedHashSet<>(getFolders(packageName, userId));
            if (enabled) current.add(folder);
            else current.remove(folder);
            setFolders(packageName, userId, current);
        }
    }

    private static String key(String packageName, int userId) {
        return userId + "|" + packageName;
    }

    private static String key(String packageName, int userId, String folder) {
        return userId + "|" + packageName + "|" + folder;
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
