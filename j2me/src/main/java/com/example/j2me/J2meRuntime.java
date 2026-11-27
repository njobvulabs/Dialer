package com.example.j2me;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.android.dx.command.dexer.Main;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.microedition.shell.J2meEmulatorActivity;
import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.config.ProfileModel;
import ru.playsoftware.j2meloader.config.ProfilesManager;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.IOUtils;
import ru.woesss.j2me.jar.Descriptor;

public final class J2meRuntime {
    private J2meRuntime() {
    }

    public static synchronized void init(Context context) {
        Context app = context.getApplicationContext();
        ContextHolder.setAppContext(app);
    }

    public static synchronized String install(Context context, Uri source) throws Exception {
        return install(context, source, Collections.<Uri>emptyList());
    }

    public static synchronized String install(Context context, Uri source, List<Uri> extras) throws Exception {
        init(context);
        File cache = new File(context.getCacheDir(), "j2me-install");
        FileUtils.deleteDirectory(cache);
        if (!cache.mkdirs() && !cache.isDirectory()) throw new IOException("Cannot create install cache");
        try {
            File src = copyToCache(context, source, cache);
            if (extras != null) {
                for (Uri extra : extras) {
                    if (extra != null && !extra.equals(source)) copyToCache(context, extra, cache);
                }
            }
            return installFromCache(cache, src);
        } finally {
            FileUtils.deleteDirectory(cache);
        }
    }

    private static String installFromCache(File cache, File src) throws Exception {
        String name = src.getName().toLowerCase(Locale.US);
        File jar;
        Descriptor descriptor;
        if (name.endsWith(".jad")) {
            descriptor = new Descriptor(src, true);
            jar = findJar(cache, src, descriptor.getJarUrl());
            descriptor.merge(loadManifest(jar));
        } else if (name.endsWith(".jar")) {
            jar = src;
            descriptor = loadManifest(jar);
        } else if (name.endsWith(".kjx")) {
            File[] out = extractKjx(src, cache);
            File jad = out[0];
            jar = out[1];
            descriptor = new Descriptor(jad, true);
            descriptor.merge(loadManifest(jar));
        } else {
            throw new IOException("Unsupported J2ME file: " + src.getName());
        }

        String appId = makeAppId(descriptor, jar);
        File appDir = new File(Config.getAppDir(), appId);
        File tmpDir = new File(appDir.getParentFile(), ".tmp-" + appId);
        FileUtils.deleteDirectory(tmpDir);
        if (!tmpDir.mkdirs() && !tmpDir.isDirectory()) throw new IOException("Cannot create staging dir");

        File resJar = new File(tmpDir, Config.MIDLET_RES_FILE);
        copyFile(jar, resJar);
        writeManifest(new File(tmpDir, Config.MIDLET_MANIFEST_FILE), descriptor);

        try {
            File icon = extractIcon(jar, descriptor.getIcon());
            if (icon != null) copyFile(icon, new File(tmpDir, Config.MIDLET_ICON_FILE));
        } catch (Exception ignored) {
        }

        File dex = new File(tmpDir, Config.MIDLET_DEX_FILE);
        convertJarToDex(resJar, dex);

        FileUtils.deleteDirectory(appDir);
        if (!tmpDir.renameTo(appDir)) throw new IOException("Cannot move staged app into place");

        File configDir = new File(Config.getConfigsDir(), appId);
        configDir.mkdirs();
        ProfileModel profile = new ProfileModel(new File(configDir, "config.json"));
        ProfilesManager.saveConfig(profile);
        return appId;
    }

    public static Intent launchIntent(Context context, String appId) {
        init(context);
        Intent intent = new Intent(context, J2meEmulatorActivity.class);
        intent.putExtra(J2meEmulatorActivity.EXTRA_APP_ID, appId);
        intent.putExtra(J2meEmulatorActivity.EXTRA_JAR_PATH, new File(Config.getAppDir(), appId).getAbsolutePath());
        return intent;
    }

    public static synchronized List<J2meApp> listApps(Context context) {
        init(context);
        File root = new File(Config.getAppDir());
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) return Collections.emptyList();
        ArrayList<J2meApp> apps = new ArrayList<>();
        for (File dir : dirs) {
            File manifestFile = new File(dir, Config.MIDLET_MANIFEST_FILE);
            File dexFile = new File(dir, Config.MIDLET_DEX_FILE);
            if (!manifestFile.isFile() || !dexFile.isFile()) continue;
            try {
                Descriptor d = new Descriptor(manifestFile, false);
                File icon = new File(dir, Config.MIDLET_ICON_FILE);
                apps.add(new J2meApp(dir.getName(), safe(d.getName(), dir.getName()), safe(d.getVendor(), ""), safe(d.getVersion(), ""), dir.getAbsolutePath(), icon.isFile() ? icon.getAbsolutePath() : null));
            } catch (Exception ignored) {
            }
        }
        return apps;
    }

    public static synchronized void delete(Context context, String appId) {
        init(context);
        FileUtils.deleteDirectory(new File(Config.getAppDir(), appId));
        FileUtils.deleteDirectory(new File(Config.getConfigsDir(), appId));
        FileUtils.deleteDirectory(new File(Config.getDataDir(), appId));
    }

    private static String safe(String v, String def) {
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    private static File copyToCache(Context context, Uri source, File cache) throws IOException {
        String name = safeFileName(source.getLastPathSegment(), "app.bin");
        File out = uniqueFile(cache, name);
        try (InputStream in = context.getContentResolver().openInputStream(source); OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("Cannot open source URI");
            IOUtils.copy(in, os);
        }
        String ext = sniffExtension(out);
        if (ext != null && !out.getName().toLowerCase(Locale.US).endsWith(ext)) {
            File renamed = uniqueFile(cache, stripExtension(out.getName()) + ext);
            if (!out.renameTo(renamed)) throw new IOException("Cannot stage " + out.getName());
            out = renamed;
        } else if (ext == null && !hasKnownExtension(out.getName())) {
            throw new IOException("Unrecognized J2ME file: " + name);
        }
        return out;
    }

    private static String safeFileName(String raw, String fallback) {
        if (raw == null) return fallback;
        int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        String name = slash >= 0 ? raw.substring(slash + 1) : raw;
        name = name.replace('\u0000', '_').trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return fallback;
        return name;
    }

    private static File uniqueFile(File dir, String name) {
        File out = new File(dir, name);
        if (!out.exists()) return out;
        String base = stripExtension(name);
        String ext = name.substring(base.length());
        for (int i = 1; i < 1000; i++) {
            out = new File(dir, base + " (" + i + ")" + ext);
            if (!out.exists()) return out;
        }
        return new File(dir, System.nanoTime() + ext);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name;
        return name.substring(0, dot);
    }

    private static boolean hasKnownExtension(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".jar") || lower.endsWith(".jad") || lower.endsWith(".kjx");
    }

    private static String sniffExtension(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[512];
            int read = in.read(head);
            if (read >= 3 && head[0] == 'K' && head[1] == 'J' && head[2] == 'X') return ".kjx";
            if (read >= 4 && head[0] == 'P' && head[1] == 'K' && (head[2] == 3 || head[2] == 5 || head[2] == 7)) return ".jar";
            String text = new String(head, 0, Math.max(read, 0), "UTF-8");
            if (text.contains("MIDlet-") || text.contains("Manifest-Version")) return ".jad";
        } catch (Exception ignored) {
        }
        return null;
    }

    private static File findJar(File cache, File jad, String jarUrl) throws IOException {
        if (jarUrl != null && !jarUrl.trim().isEmpty()) {
            String name = safeFileName(new File(jarUrl).getName(), "");
            if (!name.isEmpty()) {
                File direct = new File(cache, name);
                if (direct.isFile()) return direct;
                File[] matches = cache.listFiles((dir, child) -> child.equalsIgnoreCase(name));
                if (matches != null && matches.length > 0 && matches[0].isFile()) return matches[0];
            }
        }
        String alias = stripExtension(jad.getName()) + ".jar";
        File aliasFile = new File(cache, alias);
        if (aliasFile.isFile()) return aliasFile;
        File[] matches = cache.listFiles((dir, child) -> child.equalsIgnoreCase(alias));
        if (matches != null && matches.length > 0 && matches[0].isFile()) return matches[0];
        File[] jars = cache.listFiles((dir, child) -> child.toLowerCase(Locale.US).endsWith(".jar"));
        if (jars != null && jars.length == 1 && jars[0].isFile()) return jars[0];
        throw new IOException("JAR referenced by JAD not found; provide JAR/JAD together");
    }

    private static Descriptor loadManifest(File jar) throws IOException {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
            java.util.jar.Manifest m = jf.getManifest();
            if (m == null) throw new IOException("JAR manifest missing");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            m.write(bos);
            return new Descriptor(bos.toString("UTF-8"), false);
        }
    }

    private static void writeManifest(File file, Descriptor descriptor) throws IOException {
        descriptor.writeTo(file);
    }

    private static void convertJarToDex(File jar, File dex) throws Exception {
        File parent = dex.getParentFile();
        if (parent != null) parent.mkdirs();
        try {
            Main.main(new String[]{"--no-optimize", "--core-library", "--output=" + dex.getAbsolutePath(), jar.getAbsolutePath()});
        } catch (Throwable e) {
            throw new IOException("Could not convert J2ME JAR to DEX: " + safe(e.getMessage(), e.getClass().getSimpleName()), e);
        }
    }

    private static String makeAppId(Descriptor d, File jar) throws Exception {
        String base = safe(d.getName(), jar.getName());
        base = base.toLowerCase(Locale.US).replaceAll("[^a-z0-9._-]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "app";
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream in = new FileInputStream(jar)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) md.update(buf, 0, r);
        }
        byte[] dig = md.digest();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4 && i < dig.length; i++) sb.append(String.format(Locale.US, "%02x", dig[i]));
        return base + "_" + sb;
    }

    private static File extractIcon(File jar, String iconPath) {
        if (iconPath == null) return null;
        iconPath = iconPath.trim();
        while (iconPath.startsWith("/")) iconPath = iconPath.substring(1);
        if (iconPath.isEmpty()) return null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(jar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName();
                while (n.startsWith("/")) n = n.substring(1);
                if (!n.equals(iconPath)) continue;
                File out = File.createTempFile("j2me-icon", ".png");
                try (OutputStream os = new FileOutputStream(out)) {
                    IOUtils.copy(zis, os);
                }
                return out;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static File[] extractKjx(File src, File cache) throws Exception {
        try (java.io.DataInputStream dis = new java.io.DataInputStream(new FileInputStream(src))) {
            byte[] magic = new byte[3];
            dis.readFully(magic);
            if (magic[0] != 'K' || magic[1] != 'J' || magic[2] != 'X') throw new IOException("Invalid KJX");
            dis.readByte();
            byte lenKjxName = dis.readByte();
            if (lenKjxName > 0) dis.skipBytes(lenKjxName & 0xff);
            int lenJad = dis.readUnsignedShort();
            byte lenJadName = dis.readByte();
            byte[] jadNameBytes = new byte[lenJadName & 0xff];
            dis.readFully(jadNameBytes);
            String jadName = new String(jadNameBytes, "UTF-8");
            File jad = new File(cache, jadName);
            try (FileOutputStream fos = new FileOutputStream(jad)) {
                byte[] buf = new byte[2048];
                int rest = lenJad;
                while (rest > 0) {
                    int r = dis.read(buf, 0, Math.min(rest, buf.length));
                    if (r == -1) throw new IOException("Unexpected EOF in KJX");
                    fos.write(buf, 0, r);
                    rest -= r;
                }
            }
            String jarName = jadName.toLowerCase(Locale.US).endsWith(".jad") ? jadName.substring(0, jadName.length() - 4) + ".jar" : jadName + ".jar";
            File jar = new File(cache, jarName);
            try (FileOutputStream fos = new FileOutputStream(jar)) {
                IOUtils.copy(dis, fos);
            }
            return new File[]{jad, jar};
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        File p = dst.getParentFile();
        if (p != null) p.mkdirs();
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            IOUtils.copy(in, out);
        }
    }
}
