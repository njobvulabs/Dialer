package top.niunaijun.blackbox.utils.compat;

import android.os.Bundle;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Minimal, dependency-free reader for the binary {@code AndroidManifest.xml} stored inside an APK.
 *
 * <p>Why this exists: several APK-container / compatibility runtimes (HarmonyOS NEXT / 卓易通)
 * implement {@code PackageManager.getPackageArchiveInfo()} only partially — the returned
 * {@code PackageInfo} carries a package name but no component arrays. An app installed purely
 * inside the virtual space is also invisible to the host PackageManager, so there is no other
 * place to recover its activities from. Reading the manifest bytes ourselves works on every
 * Android version and does not need hidden APIs or the host package database.</p>
 *
 * <p>Only data needed by the virtual framework is extracted: the manifest package, the four
 * component types with their class names / exported flag / process / authorities, and the
 * actions + categories of their intent filters (used to rebuild MAIN/LAUNCHER resolution).</p>
 */
public final class ApkManifestParser {
    private static final String TAG = "ApkManifestParser";

    public static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    public static final String ACTION_MAIN = "android.intent.action.MAIN";
    public static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER";

    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_XML = 0x0003;
    private static final int CHUNK_START_ELEMENT = 0x0102;
    private static final int CHUNK_END_ELEMENT = 0x0103;

    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_REFERENCE = 0x01;
    private static final int TYPE_NULL = 0x00;
    private static final int TYPE_FLOAT = 0x04;
    private static final int TYPE_DIMENSION = 0x05;
    private static final int TYPE_FRACTION = 0x06;
    private static final int TYPE_INT_DEC = 0x10;
    private static final int TYPE_INT_HEX = 0x11;
    private static final int TYPE_INT_BOOLEAN = 0x12;
    private static final int TYPE_FIRST_COLOR_INT = 0x1c;
    private static final int TYPE_LAST_COLOR_INT = 0x1f;
    private static final int UTF8_FLAG = 0x00000100;
    private static final int NO_ENTRY = -1; // 0xFFFFFFFF as a signed int

    /**
     * A full AXML parse opens the APK zip, decodes the whole string pool and walks the chunk
     * tree twice. Before this cache a single launch parsed the same manifest 2~3 times
     * (prefetch + launcher fallback + on-screen diagnostics) and installs did it 3 times.
     * Keyed by path + size + mtime so replacing the APK invalidates the entry automatically.
     */
    private static final LruCache<String, ManifestData> sCache = new LruCache<>(16);

    /** Drop cached manifests, e.g. after an install/uninstall changes the stored APK. */
    public static void clearCache() {
        sCache.evictAll();
    }

    private static String cacheKey(String apkPath) {
        try {
            File f = new File(apkPath);
            return apkPath + "|" + f.length() + "|" + f.lastModified();
        } catch (Throwable t) {
            return apkPath;
        }
    }

    private ApkManifestParser() {
    }

    /**
     * One attribute with its original AXML type. {@code meta-data} needs the real type: a
     * reference such as {@code @integer/google_play_services_version} is compiled by aapt2 to an
     * int (or a TYPE_REFERENCE carrying the resource id) and GMS reads it back with
     * {@code Bundle.getInt()}. Collapsing it to a String would make every GMS version check fail.
     */
    public static final class Attr {
        public final int type;
        public final int data;
        public final String string;

        Attr(int type, int data, String string) {
            this.type = type;
            this.data = data;
            this.string = string;
        }

        boolean isString() {
            return type == TYPE_STRING && string != null;
        }

        boolean isIntLike() {
            return type == TYPE_REFERENCE
                    || (type >= TYPE_INT_DEC && type <= TYPE_INT_BOOLEAN)
                    || (type >= TYPE_FIRST_COLOR_INT && type <= TYPE_LAST_COLOR_INT);
        }
    }

    /** Attribute set of one element: the legacy string view plus the typed view. */
    public static final class Attributes {
        private final Map<String, String> strings = new HashMap<>();
        private final Map<String, Attr> typed = new HashMap<>();

        public String get(String name) {
            return strings.get(name);
        }

        public Attr typed(String name) {
            return typed.get(name);
        }

        private void put(String key, String value, Attr attr) {
            if (value != null) strings.put(key, value);
            if (attr != null) typed.put(key, attr);
        }
    }

    public static final class ComponentData {
        public String type;            // activity / activity-alias / service / receiver / provider
        public String name;            // fully qualified class name
        public String targetActivity; // for activity-alias
        public boolean exported;
        public String processName;
        public String authority;
        public final ArrayList<String> actions = new ArrayList<>();
        public final ArrayList<String> categories = new ArrayList<>();
        private Bundle metaData;

        /** Lazily created component-level {@code <meta-data>} bundle. */
        public Bundle metaData() {
            if (metaData == null) metaData = new Bundle();
            return metaData;
        }

        /** @return the bundle or null when the component declares no meta-data. */
        public Bundle peekMetaData() {
            return metaData;
        }

        public boolean isLauncher() {
            return actions.contains(ACTION_MAIN) && categories.contains(CATEGORY_LAUNCHER);
        }
    }

    public static final class ManifestData {
        public String packageName;
        public String apkPath;
        public String launcherActivity;
        public final ArrayList<ComponentData> activities = new ArrayList<>();
        public final ArrayList<ComponentData> services = new ArrayList<>();
        public final ArrayList<ComponentData> receivers = new ArrayList<>();
        public final ArrayList<ComponentData> providers = new ArrayList<>();
        private Bundle appMetaData;

        /** Lazily created application-level {@code <meta-data>} bundle. */
        public Bundle appMetaData() {
            if (appMetaData == null) appMetaData = new Bundle();
            return appMetaData;
        }

        /** @return the application-level meta-data or null when the manifest has none. */
        public Bundle getAppMetaData() {
            return appMetaData;
        }

        public boolean hasComponents() {
            return !activities.isEmpty() || !services.isEmpty()
                    || !receivers.isEmpty() || !providers.isEmpty();
        }

        public String counts() {
            return "A=" + activities.size() + " S=" + services.size()
                    + " R=" + receivers.size() + " P=" + providers.size();
        }
    }

    /** Parse the manifest of an APK. Results are cached by path + size + mtime. */
    public static ManifestData parse(String apkPath) {
        if (apkPath == null) return null;
        String key = cacheKey(apkPath);
        ManifestData cached = sCache.get(key);
        if (cached != null) return cached;
        byte[] xml;
        try {
            xml = readManifest(apkPath);
        } catch (Throwable t) {
            Log.w(TAG, "readManifest failed for " + apkPath + ": " + t);
            return null;
        }
        if (xml == null) return null;
        ManifestData result;
        try {
            result = parseXml(xml, apkPath);
        } catch (Throwable t) {
            Log.w(TAG, "parseXml failed for " + apkPath + ": " + t);
            return null;
        }
        if (result != null) {
            sCache.put(key, result);
        }
        return result;
    }

    private static byte[] readManifest(String apkPath) throws IOException {
        ZipFile zip = null;
        try {
            zip = new ZipFile(apkPath);
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null) return null;
            InputStream in = zip.getInputStream(entry);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toByteArray();
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static ManifestData parseXml(byte[] xml, String apkPath) throws IOException {
        if (xml.length < 8 || u16(xml, 0) != CHUNK_XML) {
            throw new IOException("not a binary AndroidManifest.xml");
        }
        int total = (int) Math.min((long) u32(xml, 4), (long) xml.length);
        int firstChunk = u16(xml, 2); // headerSize of the XML chunk (normally 8)
        if (firstChunk < 8) firstChunk = 8;

        // Pass 1: locate the string pool (must appear before any element chunk).
        String[] pool = null;
        int p = firstChunk;
        while (p + 8 <= total) {
            int type = u16(xml, p);
            int headerSize = u16(xml, p + 2);
            int size = u32(xml, p + 4);
            if (size < 8 || headerSize < 8 || (long) p + size > total) break;
            if (type == CHUNK_STRING_POOL && pool == null) {
                pool = readStringPool(xml, p, size);
            }
            p += size;
        }
        if (pool == null) throw new IOException("string pool missing");

        // Pass 2: walk element chunks and collect components.
        ManifestData data = new ManifestData();
        data.apkPath = apkPath;
        ArrayList<String> stack = new ArrayList<>();
        ComponentData current = null;
        int currentDepth = -1;
        boolean inApplication = false;
        int applicationDepth = -1;
        boolean inFilter = false;
        int filterDepth = -1;

        p = firstChunk;
        while (p + 8 <= total) {
            int type = u16(xml, p);
            int headerSize = u16(xml, p + 2);
            int size = u32(xml, p + 4);
            if (size < 8 || headerSize < 8 || (long) p + size > total) break;

            if (type == CHUNK_START_ELEMENT && size >= 36) {
                String tag = str(pool, u32(xml, p + 20));
                Attributes attrs = readAttributes(xml, pool, p, size);
                stack.add(tag == null ? "" : tag);
                int depth = stack.size();

                if ("manifest".equals(tag)) {
                    String pkg = attrs.get("package");
                    if (pkg != null && !pkg.isEmpty()) data.packageName = pkg;
                } else if ("application".equals(tag)) {
                    inApplication = true;
                    applicationDepth = depth;
                } else if ("meta-data".equals(tag)) {
                    // GMS (and many SDKs) read meta-data at runtime through
                    // getApplicationInfo(..., GET_META_DATA). On API 33+ container runtimes the
                    // public PackageInfo carries no meta-data at all, so rebuild it from AXML.
                    Bundle target = null;
                    if (current != null && depth > currentDepth) {
                        target = current.metaData();          // activity / service / receiver / provider
                    } else if (inApplication && depth == applicationDepth + 1) {
                        target = data.appMetaData();          // <application> direct child
                    }
                    if (target != null) {
                        String name = attrs.get("android:name");
                        if (name != null && !name.isEmpty()) {
                            Attr resource = attrs.typed("android:resource");
                            if (resource != null && !resource.isString() && resource.data != 0) {
                                target.putInt(name, resource.data);
                            } else {
                                putTypedValue(target, name, attrs.typed("android:value"), data.apkPath);
                            }
                        }
                    }
                } else if (inApplication && isComponentTag(tag)) {
                    ComponentData c = buildComponent(tag, attrs, data.packageName);
                    if (c != null && c.name != null) {
                        addComponent(data, c);
                        current = c;
                        currentDepth = depth;
                    }
                } else if ("intent-filter".equals(tag) && current != null) {
                    inFilter = true;
                    filterDepth = depth;
                } else if (inFilter && current != null && "action".equals(tag)) {
                    String v = attrs.get("android:name");
                    if (v != null && !v.isEmpty()) current.actions.add(v);
                } else if (inFilter && current != null && "category".equals(tag)) {
                    String v = attrs.get("android:name");
                    if (v != null && !v.isEmpty()) current.categories.add(v);
                }
            } else if (type == CHUNK_END_ELEMENT) {
                int depth = stack.size();
                if (inFilter && depth == filterDepth) inFilter = false;
                if (current != null && depth == currentDepth) current = null;
                if (inApplication && depth == applicationDepth) inApplication = false;
                if (depth > 0) stack.remove(depth - 1);
            }
            p += size;
        }
        for (ComponentData c : data.activities) {
            if (c.isLauncher()) {
                data.launcherActivity = c.name;
                break;
            }
        }
        return data;
    }

    /** Mirrors android.content.pm.PackageParser#parseMetaData typed-value handling. */
    private static void putTypedValue(Bundle bundle, String name, Attr v, String apkPath) {
        if (v == null) return;
        if (v.isString()) {
            bundle.putString(name, v.string);
        } else if (v.type == TYPE_INT_BOOLEAN) {
            bundle.putBoolean(name, v.data != 0);
        } else if (v.type == TYPE_FLOAT) {
            bundle.putFloat(name, Float.intBitsToFloat(v.data));
        } else if (v.type == TYPE_REFERENCE) {
            // A manifest value such as @integer/google_play_services_version is stored as a
            // reference. The platform resolves it, and SDKs depend on that: GMS compares
            // com.google.android.gms.version against 12451000, not against 0x7f0a000f.
            ApkResourceTable.Value resolved = ApkResourceTable.lookup(apkPath, v.data);
            if (resolved != null && resolved.type != TYPE_NULL) {
                if (resolved.type == TYPE_STRING && resolved.string != null) {
                    bundle.putString(name, resolved.string);
                    return;
                }
                if (resolved.isFloat()) {
                    bundle.putFloat(name, Float.intBitsToFloat(resolved.data));
                    return;
                }
                if (resolved.isBoolean()) {
                    bundle.putBoolean(name, resolved.data != 0);
                    return;
                }
                bundle.putInt(name, resolved.data);
                return;
            }
            // Unresolvable (missing resource table): keep the raw id, same as before.
            bundle.putInt(name, v.data);
        } else {
            // TYPE_INT_DEC/HEX, colors, dimensions and fractions all carry data as an int.
            bundle.putInt(name, v.data);
        }
    }

    private static boolean isComponentTag(String tag) {
        return "activity".equals(tag) || "activity-alias".equals(tag)
                || "service".equals(tag) || "receiver".equals(tag)
                || "provider".equals(tag);
    }

    private static void addComponent(ManifestData data, ComponentData c) {
        if ("service".equals(c.type)) {
            data.services.add(c);
        } else if ("receiver".equals(c.type)) {
            data.receivers.add(c);
        } else if ("provider".equals(c.type)) {
            if (c.authority != null && !c.authority.isEmpty()) data.providers.add(c);
        } else {
            data.activities.add(c);
        }
    }

    private static ComponentData buildComponent(String tag, Attributes attrs, String pkg) {
        ComponentData c = new ComponentData();
        c.type = tag;
        String raw = attrs.get("android:name");
        String aliasTarget = attrs.get("android:targetActivity");
        String chosen = ("activity-alias".equals(tag) && aliasTarget != null) ? aliasTarget : raw;
        c.name = qualify(pkg, chosen);
        c.targetActivity = qualify(pkg, aliasTarget);
        String exported = attrs.get("android:exported");
        if (exported != null) {
            c.exported = "true".equalsIgnoreCase(exported)
                    || "1".equals(exported) || "-1".equals(exported);
        }
        // A non-string AXML value means a resource reference (a numeric id). Using it as a
        // process name / authority would corrupt the generated ComponentInfo, so drop it.
        c.processName = dropResourceRef(attrs.get("android:process"));
        c.authority = dropResourceRef(attrs.get("android:authorities"));
        return c;
    }

    private static String dropResourceRef(String value) {
        if (value == null || value.isEmpty()) return null;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return value;
        }
        return null;
    }

    private static String qualify(String pkg, String name) {
        if (name == null || name.isEmpty()) return null;
        if (name.startsWith(".")) return pkg == null ? null : pkg + name;
        if (name.indexOf('.') >= 0) return name;
        return pkg == null ? null : pkg + "." + name;
    }

    private static Attributes readAttributes(byte[] xml, String[] pool,
                                             int chunkStart, int chunkSize) {
        Attributes map = new Attributes();
        if (chunkStart + 36 > xml.length) return map;
        int attrStart = u16(xml, chunkStart + 24);
        int attrSize = u16(xml, chunkStart + 26);
        int attrCount = u16(xml, chunkStart + 28);
        if (attrSize < 20) attrSize = 20;
        int base = chunkStart + 16 + attrStart;
        int end = Math.min(chunkStart + chunkSize, xml.length);
        for (int i = 0; i < attrCount; i++) {
            int a = base + i * attrSize;
            if (a + 20 > end) break;
            int nsIdx = u32(xml, a);
            int nameIdx = u32(xml, a + 4);
            int rawIdx = u32(xml, a + 8);
            int dataType = xml[a + 15] & 0xff;
            int data = u32(xml, a + 16);
            String attrName = str(pool, nameIdx);
            if (attrName == null || attrName.isEmpty()) continue;
            String ns = (nsIdx == NO_ENTRY || nsIdx < 0) ? null : str(pool, nsIdx);
            String value;
            String stringValue = null;
            if (dataType == TYPE_STRING) {
                value = str(pool, data);
                stringValue = value;
            } else if (rawIdx != NO_ENTRY && rawIdx >= 0) {
                value = str(pool, rawIdx);
            } else if (dataType == TYPE_INT_BOOLEAN) {
                value = data != 0 ? "true" : "false";
            } else {
                value = String.valueOf(data);
            }
            if (value == null) continue;
            Attr attr = new Attr(dataType, data, stringValue);
            if (ANDROID_NS.equals(ns)) {
                map.put("android:" + attrName, value, attr);
            } else if (ns == null || ns.isEmpty()) {
                map.put(attrName, value, attr);
            }
        }
        return map;
    }

    static String[] readStringPool(byte[] xml, int start, int size) {
        if (start + 28 > xml.length) return new String[0];
        int count = u32(xml, start + 8);
        int flags = u32(xml, start + 16);
        int stringsStart = u32(xml, start + 20);
        boolean utf8 = (flags & UTF8_FLAG) != 0;
        if (count <= 0 || count > 200000 || stringsStart <= 0) return new String[0];
        if ((long) start + stringsStart > xml.length) return new String[0];
        String[] pool = new String[count];
        int offsets = start + 28;
        int dataStart = start + stringsStart;
        for (int i = 0; i < count; i++) {
            if (offsets + i * 4 + 4 > xml.length) {
                pool[i] = "";
                continue;
            }
            int off = u32(xml, offsets + i * 4);
            int pos = dataStart + off;
            if (pos < 0 || pos + 2 > xml.length) {
                pool[i] = "";
                continue;
            }
            try {
                Cursor c = new Cursor(xml, pos);
                if (utf8) {
                    c.len8();                 // UTF-16 length (unused)
                    int byteLen = c.len8();   // UTF-8 byte length
                    if (byteLen < 0 || c.p + byteLen > xml.length) {
                        pool[i] = "";
                        continue;
                    }
                    pool[i] = new String(xml, c.p, byteLen, "UTF-8");
                } else {
                    int len = c.u16();
                    if ((len & 0x8000) != 0) len = ((len & 0x7fff) << 16) | c.u16();
                    if (len < 0 || c.p + 2L * len > xml.length) {
                        pool[i] = "";
                        continue;
                    }
                    char[] chars = new char[len];
                    for (int j = 0; j < len; j++) chars[j] = (char) c.u16();
                    pool[i] = new String(chars);
                }
            } catch (Throwable t) {
                pool[i] = "";
            }
        }
        return pool;
    }

    static String str(String[] pool, int idx) {
        if (pool == null || idx < 0 || idx >= pool.length) return null;
        return pool[idx];
    }

    static int u16(byte[] b, int p) {
        return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8);
    }

    static int u32(byte[] b, int p) {
        return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8)
                | ((b[p + 2] & 0xff) << 16) | ((b[p + 3] & 0xff) << 24);
    }

    private static final class Cursor {
        final byte[] b;
        int p;

        Cursor(byte[] b, int p) {
            this.b = b;
            this.p = p;
        }

        int u8() {
            return b[p++] & 0xff;
        }

        int u16() {
            return u8() | (u8() << 8);
        }

        /** 1-or-2 byte length used by UTF-8 string pools. */
        int len8() {
            int v = u8();
            if ((v & 0x80) != 0) v = ((v & 0x7f) << 8) | u8();
            return v;
        }
    }
}
