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
        init(context);
        File cache = new File(context.getCacheDir(), "j2me-install");
        FileUtils.deleteDirectory(cache);
        if (!cache.mkdirs() && !cache.isDirectory()) throw new IOException("Cannot create install cache");
        File src = copyToCache(context, source, cache);
        String name = src.getName().toLowerCase(Locale.US);
        File jar;
        Descriptor descriptor;
        if (name.endsWith(".jad")) {
            descriptor = new Descriptor(src, true);
            String jarUrl = descriptor.getJarUrl();
            if (jarUrl == null) throw new IOException("JAD missing MIDlet-Jar-URL");
            File jarCandidate = new File(src.getParentFile(), jarUrl);
            if (!jarCandidate.exists()) jarCandidate = new File(src.getParentFile(), new File(jarUrl).getName());
            if (!jarCandidate.exists()) throw new IOException("JAR referenced by JAD not found; provide JAR/JAD together");
            jar = jarCandidate;
            Descriptor jarDesc = loadManifest(jar);
            descriptor.merge(jarDesc);
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
        FileUtils.deleteDirectory(appDir);
        if (!appDir.mkdirs() && !appDir.isDirectory()) throw new IOException("Cannot create app dir");

        File resJar = new File(appDir, Config.MIDLET_RES_FILE);
        copyFile(jar, resJar);
        writeManifest(new File(appDir, Config.MIDLET_MANIFEST_FILE), descriptor);

        File icon = extractIcon(jar, descriptor.getIcon());
        if (icon != null) {
            File iconOut = new File(appDir, Config.MIDLET_ICON_FILE);
            copyFile(icon, iconOut);
        }

        File dex = new File(appDir, Config.MIDLET_DEX_FILE);
        convertJarToDex(resJar, dex);

        File configDir = new File(Config.getConfigsDir(), appId);
        configDir.mkdirs();
        ProfileModel profile = new ProfileModel(new File(configDir, "config.json"));
        ProfilesManager.saveConfig(profile);

        FileUtils.deleteDirectory(cache);
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
        String name = "app.jar";
        String last = source.getLastPathSegment();
        if (last != null) {
            int slash = last.lastIndexOf('/');
            if (slash >= 0) last = last.substring(slash + 1);
            if (!last.trim().isEmpty()) name = last;
        }
        File out = new File(cache, name);
        try (InputStream in = context.getContentResolver().openInputStream(source); OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("Cannot open source URI");
            IOUtils.copy(in, os);
        }
        return out;
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
        Main.main(new String[]{"--dex", "--no-strict", "--output=" + dex.getAbsolutePath(), jar.getAbsolutePath()});
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
