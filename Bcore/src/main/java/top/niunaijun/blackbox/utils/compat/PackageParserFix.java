package top.niunaijun.blackbox.utils.compat;

import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PackageParserFix — replaces android.content.pm.PackageParser removed in Android 14 (API 34).
 *
 * <p>On top of the public-PM parsing path this class also provides a three-stage component
 * recovery strategy. Some APK-container runtimes (HarmonyOS NEXT / 卓易通) return a
 * {@code PackageInfo} with a package name but empty component arrays from
 * {@code getPackageArchiveInfo()}, and apps installed only inside the virtual space are not
 * visible to the host PackageManager at all. The last stage therefore parses the binary
 * AndroidManifest.xml from the APK bytes directly.</p>
 */
public class PackageParserFix {
    private static final String TAG = "PackageParserFix";
    private static final Map<String, String> sLastSources = new ConcurrentHashMap<>();

    @SuppressWarnings("deprecation")
    private static int getFullFlags() {
        int flags = getComponentFlags() | PackageManager.GET_PERMISSIONS;
        if (Build.VERSION.SDK_INT >= 28) {
            flags |= PackageManager.GET_SIGNING_CERTIFICATES;
        } else {
            flags |= PackageManager.GET_SIGNATURES;
        }
        return flags;
    }

    private static int getComponentFlags() {
        return PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS
                | PackageManager.GET_PROVIDERS
                | PackageManager.GET_META_DATA;
    }

    public static boolean isReadableApk(String path) {
        if (path == null) return false;
        try {
            File f = new File(path);
            return f.isFile() && f.length() > 1024;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Parse an APK through the public PackageManager. Tries several flag sets because some
     * container runtimes reject GET_SIGNING_CERTIFICATES and then return an empty component set.
     * Always returns the best PackageInfo obtained, even when it has no components (the caller
     * may still need applicationInfo / version data).
     */
    public static PackageInfo parseApk(Context context, String apkPath) {
        if (!isReadableApk(apkPath)) return null;
        PackageManager pm = context.getPackageManager();
        int[] flagSets = {getFullFlags(), getComponentFlags(), PackageManager.GET_ACTIVITIES};
        PackageInfo best = null;
        for (int flags : flagSets) {
            try {
                PackageInfo pi = pm.getPackageArchiveInfo(apkPath, flags);
                if (pi == null) continue;
                if (pi.applicationInfo != null) {
                    pi.applicationInfo.sourceDir = apkPath;
                    pi.applicationInfo.publicSourceDir = apkPath;
                }
                if (best == null) best = pi;
                Log.d(TAG, "parseApk " + pi.packageName + " flags=" + flags
                        + " components: A=" + count(pi.activities)
                        + " S=" + count(pi.services)
                        + " R=" + count(pi.receivers)
                        + " P=" + count(pi.providers));
                if (hasComponents(pi)) return pi;
            } catch (Throwable t) {
                Log.w(TAG, "parseApk flags=" + flags + " failed: " + t);
            }
        }
        if (best != null) {
            Log.w(TAG, "parseApk " + best.packageName
                    + " returned no components with any flag set (SDK=" + Build.VERSION.SDK_INT + ")");
        }
        return best;
    }

    /** Reads the components of a package already installed on the host. */
    public static PackageInfo getInstalledPackageInfo(Context context, String packageName) {
        if (packageName == null) return null;
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, getFullFlags());
            if (pi != null) {
                Log.d(TAG, "getInstalledPackageInfo " + packageName
                        + " components: A=" + count(pi.activities)
                        + " S=" + count(pi.services)
                        + " R=" + count(pi.receivers)
                        + " P=" + count(pi.providers));
            }
            return pi;
        } catch (Exception e) {
            Log.w(TAG, "getInstalledPackageInfo " + packageName + " failed: " + e.getMessage());
            return null;
        }
    }

    public static boolean hasComponents(PackageInfo pi) {
        return pi != null && ((pi.activities != null && pi.activities.length > 0)
                || (pi.services != null && pi.services.length > 0)
                || (pi.receivers != null && pi.receivers.length > 0)
                || (pi.providers != null && pi.providers.length > 0));
    }

    private static int count(Object[] arr) {
        return arr == null ? 0 : arr.length;
    }

    /** Result of a component recovery attempt, including the source used (for on-screen diagnostics). */
    public static final class ComponentResult {
        public PackageInfo info;
        public String source = "none";
        public String apkPath;
        public String launcherActivity;
        public final HashMap<String, IntentFilter> activityFilters = new HashMap<>();

        public boolean ok() {
            return hasComponents(info);
        }

        public String counts() {
            if (info == null) return "NULL";
            return "A=" + count(info.activities) + " S=" + count(info.services)
                    + " R=" + count(info.receivers) + " P=" + count(info.providers);
        }
    }

    /**
     * Recover the component tree of a package without relying on a single API surface.
     * Order: host PackageManager → public archive parser → raw AndroidManifest.xml (AXML).
     *
     * @param packageName      virtual package name
     * @param apkPath           the APK inside the virtual space (or the source APK being installed)
     * @param appInfoFallback   ApplicationInfo to attach to synthesized ComponentInfo objects
     */
    public static ComponentResult resolveComponents(Context context, String packageName,
                                                      String apkPath, ApplicationInfo appInfoFallback) {
        ComponentResult r = new ComponentResult();
        r.apkPath = apkPath;
        boolean apkAvailable = isReadableApk(apkPath);

        // Public PackageInfo never carries intent filters, so parse the manifest up front and
        // keep the MAIN/LAUNCHER filters regardless of which source supplies the components.
        ApkManifestParser.ManifestData axml = null;
        if (apkAvailable) {
            try {
                axml = ApkManifestParser.parse(apkPath);
                if (axml != null) {
                    r.launcherActivity = axml.launcherActivity;
                    for (ApkManifestParser.ComponentData c : axml.activities) {
                        if (c.name == null || c.actions.isEmpty()) continue;
                        IntentFilter filter = new IntentFilter();
                        for (String action : c.actions) filter.addAction(action);
                        for (String category : c.categories) filter.addCategory(category);
                        r.activityFilters.put(c.name, filter);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "AXML prefetch failed for " + apkPath + ": " + t);
            }
        }

        // 1) Apps cloned from a host-installed package: the host PM has the fully parsed tree.
        try {
            PackageInfo host = getInstalledPackageInfo(context, packageName);
            if (hasComponents(host)) {
                r.info = host;
                r.source = "host";
                noteSource(packageName, "host");
                return r;
            }
        } catch (Throwable t) {
            Log.w(TAG, "host component lookup failed for " + packageName + ": " + t);
        }

        // 2) Public archive parser with several flag sets.
        if (apkAvailable) {
            try {
                PackageInfo archive = parseApk(context, apkPath);
                if (hasComponents(archive)) {
                    r.info = archive;
                    r.source = "archive";
                    noteSource(packageName, "archive");
                    return r;
                }
            } catch (Throwable t) {
                Log.w(TAG, "archive component lookup failed for " + apkPath + ": " + t);
            }
        }

        // 3) Last resort: read the binary manifest ourselves. This is the only source that
        //    works for an APK installed purely inside the virtual space on a container where
        //    getPackageArchiveInfo() does not enumerate components.
        if (axml != null && axml.hasComponents()) {
            try {
                PackageInfo pi = buildPackageInfoFromManifest(axml, packageName, appInfoFallback);
                if (pi != null && hasComponents(pi)) {
                    r.info = pi;
                    r.source = "axml";
                    noteSource(packageName, "axml(" + axml.counts() + ")");
                    return r;
                }
            } catch (Throwable t) {
                Log.w(TAG, "AXML component synthesis failed for " + apkPath + ": " + t);
            }
        } else if (apkAvailable) {
            Log.w(TAG, "AXML parse yielded no components for " + apkPath);
        }

        noteSource(packageName, apkAvailable ? "none" : "none/no-apk");
        return r;
    }

    /** Copy recovered component arrays into an existing PackageInfo, keeping its other fields. */
    public static void copyComponents(PackageInfo dst, PackageInfo src) {
        if (dst == null || src == null) return;
        dst.activities = src.activities;
        dst.services = src.services;
        dst.receivers = src.receivers;
        dst.providers = src.providers;
        if (dst.applicationInfo == null) dst.applicationInfo = src.applicationInfo;
        fillComponentApplicationInfo(dst);
    }

    private static void fillComponentApplicationInfo(PackageInfo pi) {
        if (pi == null || pi.applicationInfo == null) return;
        if (pi.activities != null) {
            for (ActivityInfo a : pi.activities) {
                if (a != null && a.applicationInfo == null) a.applicationInfo = pi.applicationInfo;
            }
        }
        if (pi.receivers != null) {
            for (ActivityInfo a : pi.receivers) {
                if (a != null && a.applicationInfo == null) a.applicationInfo = pi.applicationInfo;
            }
        }
        if (pi.services != null) {
            for (ServiceInfo s : pi.services) {
                if (s != null && s.applicationInfo == null) s.applicationInfo = pi.applicationInfo;
            }
        }
        if (pi.providers != null) {
            for (ProviderInfo p : pi.providers) {
                if (p != null && p.applicationInfo == null) p.applicationInfo = pi.applicationInfo;
            }
        }
    }

    private static PackageInfo buildPackageInfoFromManifest(ApkManifestParser.ManifestData manifest,
                                                            String packageName,
                                                            ApplicationInfo appInfoFallback) {
        String pkg = manifest.packageName != null ? manifest.packageName : packageName;
        if (pkg == null || pkg.isEmpty()) return null;

        ApplicationInfo appInfo;
        if (appInfoFallback != null) {
            appInfo = new ApplicationInfo(appInfoFallback);
        } else {
            appInfo = new ApplicationInfo();
        }
        if (appInfo.packageName == null || appInfo.packageName.isEmpty()) appInfo.packageName = pkg;
        if (appInfo.sourceDir == null) appInfo.sourceDir = manifest.apkPath;
        if (appInfo.publicSourceDir == null) appInfo.publicSourceDir = manifest.apkPath;
        if (appInfo.processName == null || appInfo.processName.isEmpty()) appInfo.processName = pkg;
        Bundle appMetaData = manifest.getAppMetaData();
        if (appMetaData != null && !appMetaData.isEmpty()) {
            appInfo.metaData = new Bundle(appMetaData);
        }

        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = appInfo;
        pi.versionName = "1.0";
        pi.versionCode = 1;

        ArrayList<ActivityInfo> activities = new ArrayList<>();
        ArrayList<ActivityInfo> receivers = new ArrayList<>();
        ArrayList<ServiceInfo> services = new ArrayList<>();
        ArrayList<ProviderInfo> providers = new ArrayList<>();
        for (ApkManifestParser.ComponentData c : manifest.activities) {
            activities.add(buildActivityInfo(c, pkg, appInfo));
        }
        for (ApkManifestParser.ComponentData c : manifest.receivers) {
            receivers.add(buildActivityInfo(c, pkg, appInfo));
        }
        for (ApkManifestParser.ComponentData c : manifest.services) {
            services.add(buildServiceInfo(c, pkg, appInfo));
        }
        for (ApkManifestParser.ComponentData c : manifest.providers) {
            providers.add(buildProviderInfo(c, pkg, appInfo));
        }
        pi.activities = activities.isEmpty() ? null : activities.toArray(new ActivityInfo[0]);
        pi.receivers = receivers.isEmpty() ? null : receivers.toArray(new ActivityInfo[0]);
        pi.services = services.isEmpty() ? null : services.toArray(new ServiceInfo[0]);
        pi.providers = providers.isEmpty() ? null : providers.toArray(new ProviderInfo[0]);
        return pi;
    }

    private static ActivityInfo buildActivityInfo(ApkManifestParser.ComponentData c,
                                                  String pkg, ApplicationInfo appInfo) {
        ActivityInfo ai = new ActivityInfo();
        ai.name = c.name;
        ai.packageName = pkg;
        ai.applicationInfo = appInfo;
        ai.exported = c.exported || !c.actions.isEmpty() || c.isLauncher();
        ai.enabled = true;
        ai.processName = c.processName;
        ai.metaData = c.peekMetaData();
        return ai;
    }

    private static ServiceInfo buildServiceInfo(ApkManifestParser.ComponentData c,
                                               String pkg, ApplicationInfo appInfo) {
        ServiceInfo si = new ServiceInfo();
        si.name = c.name;
        si.packageName = pkg;
        si.applicationInfo = appInfo;
        si.exported = c.exported || !c.actions.isEmpty();
        si.enabled = true;
        si.processName = c.processName;
        si.metaData = c.peekMetaData();
        return si;
    }

    private static ProviderInfo buildProviderInfo(ApkManifestParser.ComponentData c,
                                                  String pkg, ApplicationInfo appInfo) {
        ProviderInfo pi = new ProviderInfo();
        pi.name = c.name;
        pi.packageName = pkg;
        pi.applicationInfo = appInfo;
        pi.authority = c.authority;
        pi.exported = c.exported;
        pi.enabled = true;
        pi.processName = c.processName;
        pi.metaData = c.peekMetaData();
        return pi;
    }

    /**
     * Application-level {@code <meta-data>} read straight from the binary manifest. Used to heal
     * packages whose stored record has no mAppMetaData (public PM parsers on API 33+ containers
     * frequently drop it), which makes GMS/Firebase SDKs throw at Activity.onCreate.
     */
    public static Bundle readAppMetaData(String apkPath) {
        if (!isReadableApk(apkPath)) return null;
        try {
            ApkManifestParser.ManifestData m = ApkManifestParser.parse(apkPath);
            if (m == null) return null;
            Bundle b = m.getAppMetaData();
            // Never hand out the cached ManifestData bundle: callers may merge into it.
            return b == null ? null : new Bundle(b);
        } catch (Throwable t) {
            Log.w(TAG, "readAppMetaData failed for " + apkPath + ": " + t);
            return null;
        }
    }

    /** Short AXML summary used by the on-screen diagnostic report. */
    public static String getManifestSummary(String apkPath) {
        if (!isReadableApk(apkPath)) return "APK_NOT_READABLE";
        try {
            ApkManifestParser.ManifestData m = ApkManifestParser.parse(apkPath);
            if (m == null) return "PARSE_NULL";
            return m.packageName + " " + m.counts() + " launcher=" + m.launcherActivity;
        } catch (Throwable t) {
            return "ERROR " + t;
        }
    }

    public static void noteSource(String packageName, String source) {
        if (packageName != null && source != null) sLastSources.put(packageName, source);
    }

    public static String getLastSource(String packageName) {
        return packageName == null ? null : sLastSources.get(packageName);
    }

    @SuppressWarnings("deprecation")
    public static Signature[] getSignatures(PackageInfo pi) {
        if (pi == null) return new Signature[0];
        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
            Signature[] certs = pi.signingInfo.getApkContentsSigners();
            return certs != null ? certs : new Signature[0];
        }
        return pi.signatures != null ? pi.signatures : new Signature[0];
    }

    /** True on API 33+ where PackageParser is deprecated/removed */
    public static boolean needsFix() {
        return Build.VERSION.SDK_INT >= 33;
    }
}
