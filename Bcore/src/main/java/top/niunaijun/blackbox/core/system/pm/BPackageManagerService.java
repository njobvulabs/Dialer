package top.niunaijun.blackbox.core.system.pm;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.RemoteException;
import android.text.TextUtils;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.GmsCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.pm.installer.CopyExecutor;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.core.system.user.BUserInfo;
import top.niunaijun.blackbox.core.system.user.BUserManagerService;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.entity.pm.InstalledPackage;
import top.niunaijun.blackbox.utils.AbiUtils;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.PackageParserCompat;
import top.niunaijun.blackbox.utils.compat.PackageParserFix;


import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;



public class BPackageManagerService extends IBPackageManagerService.Stub implements ISystemService {
    public static final String TAG = "BPackageManagerService";
    public static BPackageManagerService sService = new BPackageManagerService();
    private final Settings mSettings = new Settings();
    private final ComponentResolver mComponentResolver;
    private static final BUserManagerService sUserManager = BUserManagerService.get();
    private final List<PackageMonitor> mPackageMonitors = new ArrayList<>();

    final Map<String, BPackageSettings> mPackages = mSettings.mPackages;
    final Object mInstallLock = new Object();
    /** Packages whose component repair was already attempted this process (avoids re-parsing APKs). */
    private final Set<String> mRepairAttempted = Collections.synchronizedSet(new HashSet<String>());
    /** Packages whose meta-data repair was already attempted this process. */
    private final Set<String> mMetaDataRepairAttempted = Collections.synchronizedSet(new HashSet<String>());
    /** The resolver index is built lazily because startup() may never run in APK containers. */
    private volatile boolean mComponentIndexBuilt = false;

    public static BPackageManagerService get() {
        return sService;
    }

    public BPackageManagerService() {
        mComponentResolver = new ComponentResolver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.PACKAGE_ADDED");
        filter.addAction("android.intent.action.PACKAGE_REMOVED");
        filter.addDataScheme("package");
        // Deliver package broadcasts on a background thread: a full scan used to run on the
        // main thread and serialize every query behind the package map lock.
        HandlerThread monitorThread =
                new HandlerThread("bb-pkg-monitor", Process.THREAD_PRIORITY_BACKGROUND);
        monitorThread.start();
        BlackBoxCore.getContext().registerReceiver(mPackageChangedHandler, filter, null,
                new Handler(monitorThread.getLooper()));
    }

    private final BroadcastReceiver mPackageChangedHandler = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!TextUtils.isEmpty(action)) {
                if ("android.intent.action.PACKAGE_ADDED".equals(action) || "android.intent.action.PACKAGE_REMOVED".equals(action)) {
                    // A single host package event only needs the matching virtual record
                    // re-scanned; a full N-package rescan is unnecessary.
                    Uri data = intent.getData();
                    String changed = data == null ? null : data.getSchemeSpecificPart();
                    if (!TextUtils.isEmpty(changed)
                            && BEnvironment.getPackageConf(changed).exists()) {
                        mSettings.scanPackage(changed);
                    } else {
                        mSettings.scanPackage();
                    }
                }
            }
        }
    };

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        if (Objects.equals(packageName, BlackBoxCore.getHostPkg())) {
            try {
                return BlackBoxCore.getPackageManager().getApplicationInfo(packageName, flags);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            return null;
        }
        flags = updateFlags(flags, userId);

        synchronized (mPackages) {

            BPackageSettings ps = mPackages.get(packageName);
            if (ps != null) {
                // GMS / Firebase read <application> meta-data on first Activity.onCreate. Records
                // persisted before the meta-data fix have mAppMetaData == null; repair on demand
                // so a stored clone never crashes with GooglePlayServicesMissingManifestValueException.
                ensureAppMetaData(ps);
                BPackage p = ps.pkg;
                return PackageManagerCompat.generateApplicationInfo(p, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public ResolveInfo resolveService(Intent intent, int flags, String resolvedType, int userId) {
        if (!sUserManager.exists(userId)) return null;
        List<ResolveInfo> query = queryIntentServicesInternal(
                intent, resolvedType, flags, userId);
        if (query != null) {
            if (query.size() >= 1) {
                
                
                return query.get(0);
            }
        }
        return null;
    }

    private List<ResolveInfo> queryIntentServicesInternal(Intent intent, String resolvedType, int flags, int userId) {
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ServiceInfo si = getServiceInfo(comp, flags, userId);
            if (si != null) {
                
                
                
                
                final ResolveInfo ri = new ResolveInfo();
                ri.serviceInfo = si;
                list.add(ri);
            }
            return list;
        }

        
        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            if (pkgName != null) {
                BPackageSettings bPackageSettings = mPackages.get(pkgName);
                if (bPackageSettings != null) {
                    final BPackage pkg = bPackageSettings.pkg;
                    return mComponentResolver.queryServices(intent, resolvedType, flags, pkg.services,
                            userId);
                }
            } else {
               return mComponentResolver.queryServices(intent, resolvedType, flags, userId);
            }
            return Collections.emptyList();
        }
    }

    @Override
    public ResolveInfo resolveActivity(Intent intent, int flags, String resolvedType, int userId) {
        if (!sUserManager.exists(userId)) return null;
        List<ResolveInfo> resolves = queryIntentActivities(intent, resolvedType, flags, userId);
        return chooseBestActivity(intent, resolvedType, flags, resolves);
    }

    @Override
    public ProviderInfo resolveContentProvider(String authority, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        return mComponentResolver.queryProvider(authority, flags, userId);
    }

    @Override
    public ResolveInfo resolveIntent(Intent intent, String resolvedType, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        List<ResolveInfo> resolves = queryIntentActivities(intent, resolvedType, flags, userId);
        return chooseBestActivity(intent, resolvedType, flags, resolves);
    }

    private ResolveInfo chooseBestActivity(Intent intent, String resolvedType,
                                           int flags, List<ResolveInfo> query) {
        if (query != null) {
            final int N = query.size();
            if (N == 1) {
                return query.get(0);
            } else if (N > 1) {
                
                
                ResolveInfo r0 = query.get(0);
                ResolveInfo r1 = query.get(1);
                
                
                if (r0.priority != r1.priority
                        || r0.preferredOrder != r1.preferredOrder
                        || r0.isDefault != r1.isDefault) {
                    return query.get(0);
                }
            }
        }
        return null;
    }

    private List<ResolveInfo> queryIntentActivities(Intent intent,
                                                    String resolvedType, int flags, int userId) {
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }

        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ActivityInfo ai = getActivity(comp, flags, userId);
            if (ai != null) {
                
                
                
                
                final ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
                return list;
            }
        }

        
        synchronized (mPackages) {
            return mComponentResolver.queryActivities(intent, resolvedType, flags, userId);
        }
    }

    @Override
    public List<ResolveInfo> queryIntentServices(
            Intent intent, int flags, int userId) {
        final String resolvedType = intent.resolveTypeIfNeeded(BlackBoxCore.getContext().getContentResolver());
        return this.queryIntentServicesInternal(intent, resolvedType, flags, userId);
    }

    private ActivityInfo getActivity(ComponentName component, int flags,
                                     int userId) {
        flags = updateFlags(flags, userId);
        synchronized (mPackages) {
            BPackage.Activity a = mComponentResolver.getActivity(component);

            if (a != null) {
                BPackageSettings ps = mSettings.mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageManagerCompat.generateActivityInfo(a, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        if (Objects.equals(packageName, BlackBoxCore.getHostPkg())) {
            try {
                return BlackBoxCore.getPackageManager().getPackageInfo(packageName, flags);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            return null;
        }

        flags = updateFlags(flags, userId);
        BPackageSettings ps = null;
        
        synchronized (mPackages) {
            
            ps = mPackages.get(packageName);
        }
        if (ps != null) {
            // Idempotent self-heal: repairs a package persisted with an empty component tree
            // (API 36 / container builds) so this query — and launcher resolution — sees
            // components even when the install record predates the fix.
            ensureComponents(ps);
            return PackageManagerCompat.generatePackageInfo(ps, flags, ps.readUserState(userId), userId);
        }
        return null;
    }

    @Override
    public ServiceInfo getServiceInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        synchronized (mPackages) {
            BPackage.Service s = mComponentResolver.getService(component);
            if (s != null) {
                BPackageSettings ps = mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageManagerCompat.generateServiceInfo(
                        s, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public ActivityInfo getReceiverInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        synchronized (mPackages) {
            BPackage.Activity a = mComponentResolver.getReceiver(component);
            if (a != null) {
                BPackageSettings ps = mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageManagerCompat.generateActivityInfo(
                        a, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        ensureComponentIndex();
        synchronized (mPackages) {
            BPackageSettings ps = mPackages.get(component.getPackageName());
            BPackage.Activity a = mComponentResolver.getActivity(component);

            // Direct scan fallback: even when the resolver index could not be built, the
            // persisted BPackage still carries the full activity list. Silently returning null
            // here is what produced a black window with no error at all.
            if (a == null && ps != null && ps.pkg != null) {
                for (BPackage.Activity candidate : ps.pkg.activities) {
                    if (candidate != null && candidate.info != null
                            && component.getClassName().equals(candidate.className)) {
                        a = candidate;
                        break;
                    }
                }
                if (a != null) {
                    prepareForIndexing(ps.pkg);
                    if (a.info.applicationInfo == null) {
                        a.info.applicationInfo = ps.pkg.applicationInfo;
                    }
                }
            }
            if (a != null && ps != null) {
                return PackageManagerCompat.generateActivityInfo(
                        a, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public ProviderInfo getProviderInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        synchronized (mPackages) {
            BPackage.Provider p = mComponentResolver.getProvider(component);
            if (p != null) {
                BPackageSettings ps = mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageManagerCompat.generateProviderInfo(
                        p, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public List<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        return getInstalledApplicationsListInternal(flags, userId, Binder.getCallingUid());
    }

    @Override
    public List<PackageInfo> getInstalledPackages(int flags, int userId) {
        final int callingUid = Binder.getCallingUid();



        if (!sUserManager.exists(userId)) return Collections.emptyList();
        ensureComponentIndex();

        
        synchronized (mPackages) {
            final int effectiveFlags = updateFlags(flags, userId);
            ArrayList<PackageInfo> list = new ArrayList<>(mPackages.size());
            for (BPackageSettings ps : mPackages.values()) {
                if (ps == null || ps.pkg == null) continue;
                ensureComponents(ps);






                // Generate directly from the in-memory record. getPackageInfo() would redo
                // sUserManager.exists, updateFlags, a map lookup and ensureComponents for
                // every package, turning one list query into N redundant lookups.
                PackageInfo pi = PackageManagerCompat.generatePackageInfo(
                        ps, effectiveFlags, ps.readUserState(userId), userId);
                if (pi != null) {
                    list.add(pi);
                }
            }
            return list;
        }
    }

    private List<ApplicationInfo> getInstalledApplicationsListInternal(int flags, int userId,
                                                                       int callingUid) {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        ensureComponentIndex();

        
        synchronized (mPackages) {
            ArrayList<ApplicationInfo> list;
            list = new ArrayList<>(mPackages.size());
            Collection<BPackageSettings> packageSettings = mPackages.values();
            for (BPackageSettings ps : packageSettings) {








                ApplicationInfo ai = PackageManagerCompat.generateApplicationInfo(ps.pkg, flags,
                        ps.readUserState(userId), userId);
                if (ai != null) {
                    list.add(ai);
                }
            }
            return list;
        }
    }

    @Override
    public List<ResolveInfo> queryIntentActivities(Intent intent, int flags, String resolvedType, int userId) throws RemoteException {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        ensureComponentIndex();
        final String pkgName = intent.getPackage();
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }

        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ActivityInfo ai = getActivityInfo(comp, flags, userId);
            if (ai != null) {
                
                
                
                
                final ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
            }
            return list;
        }

        
        List<ResolveInfo> result;
        synchronized (mPackages) {
            if (pkgName != null) {
                BPackageSettings bPackageSettings = mPackages.get(pkgName);
                result = null;
                if (bPackageSettings != null) {
                    // Safety net for records whose repair could not run at systemReady().
                    ensureComponents(bPackageSettings);
                    final BPackage pkg = bPackageSettings.pkg;

                    result = mComponentResolver.queryActivities(
                            intent, resolvedType, flags, pkg.activities, userId);
                }
                if (result == null || result.size() == 0) {
                    
                    
                    if (result == null) {
                        result = new ArrayList<>();
                    }
                }
                return result;
            }
        }
        return Collections.emptyList();
    }

    @Override
    public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags, String resolvedType, int userId) throws RemoteException {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        ensureComponentIndex();

        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ActivityInfo ai = getReceiverInfo(comp, flags, userId);
            if (ai != null) {
                
                
                
                
                ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
            }
            return list;
        }

        
        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            BPackageSettings bPackageSettings = mPackages.get(pkgName);
            if (bPackageSettings != null) {
                final BPackage pkg = bPackageSettings.pkg;
                return mComponentResolver.queryReceivers(
                        intent, resolvedType, flags, pkg.receivers, userId);
            } else {
                return mComponentResolver.queryReceivers(intent, resolvedType, flags, userId);
            }
        }
    }

    @Override
    public List<ProviderInfo> queryContentProviders(String processName, int uid, int flags, int userId) throws RemoteException {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        ensureComponentIndex();

        List<ProviderInfo> providers = new ArrayList<>();
        if (TextUtils.isEmpty(processName))
            return providers;
        providers.addAll(mComponentResolver.queryProviders(processName, null, flags, userId));
        return providers;
    }

    @Override
    public InstallResult installPackageAsUser(String file, InstallOption option, int userId) {
        synchronized (mInstallLock) {
            return installPackageAsUserLocked(file, option, userId);
        }
    }

    @Override
    public void uninstallPackageAsUser(String packageName, int userId) throws RemoteException {
        synchronized (mInstallLock) {
            synchronized (mPackages) {
                BPackageSettings ps = mPackages.get(packageName);
                if (ps == null)
                    return;

                if (!isInstalled(packageName, userId)) {
                    return;
                }
                boolean removeApp = ps.getUserState().size() <= 1;
                BProcessManagerService.get().killPackageAsUser(packageName, userId);
                int i = BPackageInstallerService.get().uninstallPackageAsUser(ps, removeApp, userId);
                if (i < 0) {
                    
                }

                if (removeApp) {
                    mSettings.removePackage(packageName);
                    mComponentResolver.removeAllComponents(ps.pkg);
                } else {
                    ps.removeUser(userId);
                    ps.save();
                }
                onPackageUninstalled(packageName, removeApp, userId);
            }
        }
    }

    @Override
    public void uninstallPackage(String packageName) {
        synchronized (mInstallLock) {
            synchronized (mPackages) {
                BPackageSettings ps = mPackages.get(packageName);
                if (ps == null)
                    return;
                BProcessManagerService.get().killAllByPackageName(packageName);
                    for (Integer userId : ps.getUserIds()) {
                        int i = BPackageInstallerService.get().uninstallPackageAsUser(ps, true, userId);
                        if (i < 0) {
                            continue;
                        }
                        onPackageUninstalled(packageName, true, userId);
                    }
                mSettings.removePackage(packageName);
                mComponentResolver.removeAllComponents(ps.pkg);
            }
        }
    }

    @Override
    public void clearPackage(String packageName, int userId) {
        if (!isInstalled(packageName, userId)) {
            return;
        }
        BProcessManagerService.get().killPackageAsUser(packageName, userId);
        BPackageSettings ps = mPackages.get(packageName);
        if (ps == null)
            return;
        int i = BPackageInstallerService.get().clearPackage(ps, userId);
    }

    @Override
    public void stopPackage(String packageName, int userId) {
        BProcessManagerService.get().killPackageAsUser(packageName, userId);
    }

    @Override
    public void deleteUser(int userId) throws RemoteException {
        synchronized (mPackages) {
            for (BPackageSettings ps : mPackages.values()) {
                uninstallPackageAsUser(ps.pkg.packageName, userId);
            }
        }
    }

    @Override
    public boolean isInstalled(String packageName, int userId) {
        if (!sUserManager.exists(userId)) return false;
        synchronized (mPackages) {
            BPackageSettings ps = mPackages.get(packageName);
            if (ps == null)
                return false;
            return ps.getInstalled(userId);
        }
    }

    @Override
    public List<InstalledPackage> getInstalledPackagesAsUser(int userId) {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        ensureComponentIndex();
        synchronized (mPackages) {
            List<InstalledPackage> installedPackages = new ArrayList<>();
            for (BPackageSettings ps : mPackages.values()) {
                if (ps.getInstalled(userId) && !GmsCore.isGoogleAppOrService(ps.pkg.packageName)) {
                    InstalledPackage installedPackage = new InstalledPackage();
                    installedPackage.userId = userId;
                    installedPackage.packageName = ps.pkg.packageName;
                    installedPackages.add(installedPackage);
                }
            }
            return installedPackages;
        }
    }

    @Override
    public String[] getPackagesForUid(int uid, int userId) throws RemoteException {
        if (!sUserManager.exists(userId)) return new String[]{};
        synchronized (mPackages) {
            List<String> packages = new ArrayList<>();
            for (BPackageSettings ps : mPackages.values()) {
                String packageName = ps.pkg.packageName;
                if (ps.getInstalled(userId) && getAppId(packageName) == uid) {
                    packages.add(packageName);
                }
            }
            if (packages.isEmpty()) {
                ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(getCallingPid());
                if (processByPid != null) {
                    packages.add(processByPid.getPackageName());
                }
            }
            return packages.toArray(new String[]{});
        }
    }

    private InstallResult installPackageAsUserLocked(String file, InstallOption option, int userId) {
        long l = System.currentTimeMillis();
        InstallResult result = new InstallResult();
        File apkFile = null;
        try {
            if (!sUserManager.exists(userId)) {
                sUserManager.createUser(userId);
            }
            if (option.isFlag(InstallOption.FLAG_URI_FILE)) {
                apkFile = new File(BEnvironment.getCacheDir(), UUID.randomUUID().toString() + ".apk");
                InputStream inputStream = BlackBoxCore.getContext().getContentResolver().openInputStream(Uri.parse(file));
                FileUtils.copyFile(inputStream, apkFile);
            } else {
                apkFile = new File(file);
            }



            PackageInfo packageArchiveInfo = BlackBoxCore.getPackageManager().getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (packageArchiveInfo == null) {
                return result.installError("getPackageArchiveInfo error.Please check whether APK is normal.");
            }

            
            String packageName = packageArchiveInfo.packageName;
            String hostPackageName = BlackBoxCore.getHostPkg();
            if (packageName.equals(hostPackageName)) {
                return result.installError("Cannot clone BlackBox app from within BlackBox. This would create infinite recursion and is not allowed for security reasons.");
            }
            
            
            if (packageName.contains("blackbox") || packageName.contains("niunaijun") || 
                packageName.contains("vspace") || packageName.contains("virtual")) {
                
                Slog.w(TAG, "Installing potentially BlackBox-related app: " + packageName + ". Proceed with caution.");
            }

            boolean support = AbiUtils.isSupport(apkFile);
            if (!support) {
                String msg = packageArchiveInfo.applicationInfo.loadLabel(BlackBoxCore.getPackageManager()) + "[" + packageArchiveInfo.packageName + "]";
                return result.installError(packageArchiveInfo.packageName,
                        msg + "\n" + (BlackBoxCore.is64Bit() ? "The box does not support 32-bit Application" : "The box does not support 64-bit Application"));
            }
            PackageParser.Package aPackage = parserApk(apkFile.getAbsolutePath());
            if (aPackage == null) {
                return result.installError("parser apk error.");
            }
            result.packageName = aPackage.packageName;

            if (option.isFlag(InstallOption.FLAG_SYSTEM)) {
                aPackage.applicationInfo = BlackBoxCore.getPackageManager().getPackageInfo(aPackage.packageName, 0).applicationInfo;
            }
            BPackageSettings bPackageSettings;
            if (PackageParserFix.needsFix()) {
                // The reflection-built Package has no components, so activities would be empty
                // and no launch intent could ever be resolved. Rebuild BPackage from a
                // PackageInfo that actually carries a component tree.
                PackageInfo fullInfo = PackageParserFix.parseApk(
                        BlackBoxCore.getContext(), apkFile.getAbsolutePath());
                if (fullInfo == null) {
                    return result.installError("parse components error.");
                }
                PackageParserFix.ComponentResult recovered = null;
                if (!PackageParserFix.hasComponents(fullInfo)) {
                    // Android 16 / APK-container builds often return no components from
                    // getPackageArchiveInfo(). Try host PM, then the raw AndroidManifest.xml —
                    // the latter is the only source for an APK installed only in the virtual space.
                    recovered = PackageParserFix.resolveComponents(BlackBoxCore.getContext(),
                            fullInfo.packageName, apkFile.getAbsolutePath(), fullInfo.applicationInfo);
                    if (recovered.ok()) {
                        PackageParserFix.copyComponents(fullInfo, recovered.info);
                        Slog.d(TAG, "Recovered components for " + fullInfo.packageName
                                + " via " + recovered.source + " " + recovered.counts());
                    } else {
                        Slog.e(TAG, "Could not recover components for " + fullInfo.packageName
                                + " (source=" + recovered.source
                                + ") - launch will likely fail with no_launch_intent");
                    }
                }
                if (option.isFlag(InstallOption.FLAG_SYSTEM)) {
                    fullInfo.applicationInfo = aPackage.applicationInfo;
                }
                BPackage newPkg = BPackage.fromPackageInfo(fullInfo, aPackage.baseCodePath);
                if (recovered != null) {
                    newPkg.attachIntentFilters(recovered.activityFilters);
                }
                bPackageSettings = mSettings.getPackageLPw(fullInfo.packageName, newPkg, option);
                // getPackageLPw() regenerates applicationInfo; a container PM can return null.
                // The component resolver dereferences processName, so never leave it null.
                if (bPackageSettings.pkg.applicationInfo == null) {
                    restoreApplicationInfo(bPackageSettings);
                }
                mRepairAttempted.remove(fullInfo.packageName);
            } else {
                bPackageSettings = mSettings.getPackageLPw(aPackage.packageName, aPackage, option);
            }

            
            BProcessManagerService.get().killPackageAsUser(aPackage.packageName, userId);

            CopyExecutor.lastError = null;
            int i = BPackageInstallerService.get().installPackageAsUser(bPackageSettings, userId);
            if (i < 0) {
                String detail;
                switch (i) {
                    case -3:
                        detail = "failed to copy native libraries";
                        break;
                    case -4:
                        detail = "failed to copy apk file";
                        break;
                    default:
                        detail = "installer step failed (" + i + ")";
                        break;
                }
                if (!TextUtils.isEmpty(CopyExecutor.lastError)) {
                    detail += " [" + CopyExecutor.lastError + "]";
                }
                Slog.e(TAG, "installPackageAsUser failed: code=" + i + ", apk=" + apkFile.getAbsolutePath());
                return result.installError("install apk error: " + detail);
            }
            synchronized (mPackages) {
                bPackageSettings.setInstalled(true, userId);
                bPackageSettings.save();
            }
            mComponentResolver.removeAllComponents(bPackageSettings.pkg);
            mComponentResolver.addAllComponents(bPackageSettings.pkg);
            mSettings.scanPackage(aPackage.packageName);
            onPackageInstalled(bPackageSettings.pkg.packageName, userId);
            return result;
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (apkFile != null && option.isFlag(InstallOption.FLAG_URI_FILE)) {
                FileUtils.deleteDir(apkFile);
            }
            Slog.d(TAG, "install finish: " + (System.currentTimeMillis() - l) + "ms");
        }
        return result;
    }

    private PackageParser.Package parserApk(String file) {
        // Android 14+ (API 33+): PackageParser is removed. Use PackageParserFix.
        if (top.niunaijun.blackbox.utils.compat.PackageParserFix.needsFix()) {
            return parserApkCompat(file);
        }
        try {
            PackageParser parser = PackageParserCompat.createParser(new File(file));
            PackageParser.Package aPackage = PackageParserCompat.parsePackage(parser, new File(file), 0);
            PackageParserCompat.collectCertificates(parser, aPackage, 0);
            return aPackage;
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return null;
    }

    /**
     * API 33+ replacement using pure reflection — avoids all PackageParser
     * inner classes (Activity, Service, etc.) which are also removed in API 34+.
     */
    private PackageParser.Package parserApkCompat(String file) {
        try {
            android.content.pm.PackageInfo pi =
                top.niunaijun.blackbox.utils.compat.PackageParserFix.parseApk(
                    BlackBoxCore.getContext(), file);
            if (pi == null) return null;

            // Instantiate PackageParser.Package via reflection (still exists as stub in API 33/34)
            Class<?> pkgClass = Class.forName("android.content.pm.PackageParser$Package");
            java.lang.reflect.Constructor<?> ctor = pkgClass.getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            PackageParser.Package pkg = (PackageParser.Package) ctor.newInstance(pi.packageName);

            // applicationInfo
            pkg.applicationInfo = pi.applicationInfo;

            // The reflection-built Package stub does not populate baseCodePath.
            // The copy step needs the source APK path to extract so / copy base.apk.
            // Without this, install always fails with "Invalid package: baseCodePath=null".
            pkg.baseCodePath = file;

            // mSignatures — set via reflection (field may be private)
            android.content.pm.Signature[] sigs =
                top.niunaijun.blackbox.utils.compat.PackageParserFix.getSignatures(pi);
            try {
                java.lang.reflect.Field sigField = pkgClass.getDeclaredField("mSignatures");
                sigField.setAccessible(true);
                sigField.set(pkg, sigs);
            } catch (Exception e) {
                Slog.w(TAG, "mSignatures field not found: " + e.getMessage());
            }

            // requestedPermissions
            if (pi.requestedPermissions != null) {
                for (String perm : pi.requestedPermissions) {
                    pkg.requestedPermissions.add(perm);
                }
            }

            // Components via BPackageManagerService helper using PackageInfo directly
            // We inject them via the BPackage path which accepts PackageInfo on API 33+
            Slog.d(TAG, "parserApkCompat OK: " + pi.packageName + " sigs=" + sigs.length);
            return pkg;
        } catch (Throwable t) {
            Slog.e(TAG, "parserApkCompat failed: " + t.getMessage());
            t.printStackTrace();
            return null;
        }
    }

    static String fixProcessName(String defProcessName, String processName) {
        if (processName == null) {
            return defProcessName;
        }
        return processName;
    }

    
    private int updateFlags(int flags, int userId) {
        if ((flags & (PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                | PackageManager.MATCH_DIRECT_BOOT_AWARE)) != 0) {
            
            
            
        } else {
            
            flags |= PackageManager.MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE;
        }
        return flags;
    }

    public int getAppId(String packageName) {
        BPackageSettings bPackageSettings = mPackages.get(packageName);
        if (bPackageSettings != null)
            return bPackageSettings.appId;
        return -1;
    }

    Settings getSettings() {
        return mSettings;
    }

    public void addPackageMonitor(PackageMonitor monitor) {
        mPackageMonitors.add(monitor);
    }

    public void removePackageMonitor(PackageMonitor monitor) {
        mPackageMonitors.remove(monitor);
    }

    void onPackageUninstalled(String packageName, boolean isRemove, int userId) {
        for (PackageMonitor packageMonitor : mPackageMonitors) {
            packageMonitor.onPackageUninstalled(packageName, isRemove, userId);
        }
        Slog.d(TAG, "onPackageUninstalled: " + packageName + ", userId: " + userId);
    }

    void onPackageInstalled(String packageName, int userId) {
        for (PackageMonitor packageMonitor : mPackageMonitors) {
            packageMonitor.onPackageInstalled(packageName, userId);
        }
        Slog.d(TAG, "onPackageInstalled: " + packageName + ", userId: " + userId);
    }

    public BPackageSettings getBPackageSetting(String packageName) {
        return mPackages.get(packageName);
    }

    public List<BPackageSettings> getBPackageSettings() {
        return new ArrayList<>(mPackages.values());
    }

    /** True when the given package has at least one registered component. */
    private static boolean hasComponents(BPackage pkg) {
        if (pkg == null) return false;
        return (pkg.activities != null && !pkg.activities.isEmpty())
                || (pkg.services != null && !pkg.services.isEmpty())
                || (pkg.receivers != null && !pkg.receivers.isEmpty())
                || (pkg.providers != null && !pkg.providers.isEmpty());
    }

    /**
     * Locate the APK backing a virtual package. A URI install moves the APK into
     * {@code blackbox/data/app/<pkg>/base.apk}; a system clone keeps pointing at the host APK.
     */
    private String findApkPath(BPackageSettings ps) {
        if (ps == null || ps.pkg == null) return null;
        String path = ps.pkg.baseCodePath;
        if (PackageParserFix.isReadableApk(path)) return path;
        File baseApk = BEnvironment.getBaseApkDir(ps.pkg.packageName);
        if (PackageParserFix.isReadableApk(baseApk.getAbsolutePath())) return baseApk.getAbsolutePath();
        ApplicationInfo ai = ps.pkg.applicationInfo;
        if (ai != null) {
            if (PackageParserFix.isReadableApk(ai.sourceDir)) return ai.sourceDir;
            if (PackageParserFix.isReadableApk(ai.publicSourceDir)) return ai.publicSourceDir;
        }
        return null;
    }

    /** Ensure the component resolver always has a non-null applicationInfo.processName to read. */
    private void restoreApplicationInfo(BPackageSettings ps) {
        if (ps == null || ps.pkg == null || ps.pkg.applicationInfo != null) return;
        String apkPath = findApkPath(ps);
        ApplicationInfo fallback = null;
        if (PackageParserFix.isReadableApk(apkPath)) {
            try {
                PackageInfo archive = BlackBoxCore.getPackageManager()
                        .getPackageArchiveInfo(apkPath, 0);
                if (archive != null) fallback = archive.applicationInfo;
            } catch (Throwable ignored) {
            }
        }
        if (fallback == null) {
            fallback = new ApplicationInfo();
            fallback.packageName = ps.pkg.packageName;
            fallback.sourceDir = apkPath;
            fallback.publicSourceDir = apkPath;
            fallback.processName = ps.pkg.packageName;
        }
        ps.pkg.applicationInfo = fallback;
    }

    /**
     * Idempotent self-heal for packages persisted with an empty activity tree.
     *
     * Runs on every framework start and whenever a package is queried. Old install records
     * written before the component-recovery fix have no activities, so no launch intent can be
     * resolved. Recovery no longer depends on the host PackageManager: for APKs installed only
     * inside the virtual space the component tree is rebuilt by parsing the binary
     * AndroidManifest.xml of the stored base.apk. The repaired record is persisted, so a user
     * who only replaces the host APK (without re-cloning) is fixed automatically.
     */
    boolean ensureComponents(BPackageSettings ps) {
        if (ps == null || ps.pkg == null) return false;
        restoreApplicationInfo(ps);
        if (ps.pkg.activities != null && !ps.pkg.activities.isEmpty()) return true;
        String pkgName = ps.pkg.packageName;
        if (TextUtils.isEmpty(pkgName) || Objects.equals(pkgName, BlackBoxCore.getHostPkg())) {
            return false;
        }
        synchronized (mRepairAttempted) {
            if (mRepairAttempted.contains(pkgName)) return false;
            mRepairAttempted.add(pkgName);
        }
        try {
            String apkPath = findApkPath(ps);
            PackageParserFix.ComponentResult recovered = PackageParserFix.resolveComponents(
                    BlackBoxCore.getContext(), pkgName, apkPath, ps.pkg.applicationInfo);
            if (!recovered.ok()) {
                Slog.w(TAG, "ensureComponents: no component source for " + pkgName
                        + " apk=" + apkPath + " source=" + recovered.source);
                return false;
            }
            if (!ps.pkg.addComponentsFromPackageInfo(recovered.info, recovered.activityFilters)) {
                return false;
            }
            ps.save();
            mComponentResolver.removeAllComponents(ps.pkg);
            mComponentResolver.addAllComponents(ps.pkg);
            Slog.d(TAG, "ensureComponents: repaired " + pkgName + " via " + recovered.source
                    + " " + recovered.counts() + " launcher=" + recovered.launcherActivity);
            return true;
        } catch (Throwable t) {
            Slog.e(TAG, "ensureComponents failed for " + pkgName + ": " + t);
            return false;
        }
    }

    /**
     * Idempotent self-heal for packages persisted without {@code <application>} meta-data.
     *
     * <p>GMS/Firebase SDKs read {@code com.google.android.gms.version} through
     * {@code getApplicationInfo(pkg, GET_META_DATA)} during the first Activity.onCreate and
     * throw {@code GooglePlayServicesMissingManifestValueException} when the bundle is null.
     * On API 33+ container runtimes none of the public parse paths fill mAppMetaData, so it is
     * recovered from the stored APK's binary manifest and merged into the existing record
     * (never overwriting keys a real parse already produced). The result is persisted, so a
     * user who only replaces the host APK — without re-cloning — is fixed automatically.</p>
     */
    boolean ensureAppMetaData(BPackageSettings ps) {
        if (ps == null || ps.pkg == null) return false;
        BPackage pkg = ps.pkg;
        String pkgName = pkg.packageName;
        if (TextUtils.isEmpty(pkgName) || Objects.equals(pkgName, BlackBoxCore.getHostPkg())) {
            return false;
        }
        synchronized (mMetaDataRepairAttempted) {
            if (mMetaDataRepairAttempted.contains(pkgName)) return false;
            mMetaDataRepairAttempted.add(pkgName);
        }
        String apkPath = findApkPath(ps);
        if (!PackageParserFix.isReadableApk(apkPath)) return false;
        try {
            Bundle fromAxml = PackageParserFix.readAppMetaData(apkPath);
            if (fromAxml == null || fromAxml.isEmpty()) return false;
            boolean changed;
            if (pkg.mAppMetaData == null) {
                pkg.mAppMetaData = fromAxml;
                changed = true;
            } else {
                changed = mergeMetaData(pkg.mAppMetaData, fromAxml);
            }
            if (!changed) return false;
            if (pkg.applicationInfo != null) {
                pkg.applicationInfo.metaData = pkg.mAppMetaData;
            }
            try {
                ps.save();
            } catch (Throwable t) {
                Slog.w(TAG, "persist meta-data failed for " + pkgName + ": " + t);
            }
            Slog.d(TAG, "ensureAppMetaData: repaired " + pkgName
                    + " keys=" + pkg.mAppMetaData.size() + " apk=" + apkPath);
            return true;
        } catch (Throwable t) {
            Slog.e(TAG, "ensureAppMetaData failed for " + pkgName + ": " + t);
            return false;
        }
    }

    /**
     * Merge manifest-derived meta-data into a stored bundle. The manifest is authoritative: a
     * record written by an older build may hold a resource id (0x7f......) where the resolved
     * value (e.g. the GMS version code) belongs, so differing values must be replaced — not just
     * missing keys added. The one exception keeps a previously resolved value when the manifest
     * now yields an unresolved reference.
     */
    private static boolean mergeMetaData(Bundle dst, Bundle src) {
        boolean changed = false;
        for (String key : src.keySet()) {
            Object incoming = src.get(key);
            if (incoming == null) continue;
            Object existing = dst.get(key);
            if (existing != null) {
                if (existing.equals(incoming)) continue;
                if (keepExisting(existing, incoming)) continue;
            }
            if (!putBundleValue(dst, key, incoming)) continue;
            changed = true;
        }
        return changed;
    }

    private static boolean keepExisting(Object existing, Object incoming) {
        if (existing instanceof Integer && incoming instanceof Integer) {
            int oldValue = (Integer) existing;
            int newValue = (Integer) incoming;
            // Never downgrade a resolved value back to an unresolved resource id.
            return isResourceId(newValue) && !isResourceId(oldValue);
        }
        return false;
    }

    private static boolean isResourceId(int value) {
        int pkgId = (value >>> 24) & 0xff;
        return pkgId == 0x7f || pkgId == 0x01;
    }

    private static boolean putBundleValue(Bundle dst, String key, Object value) {
        if (value instanceof String) dst.putString(key, (String) value);
        else if (value instanceof Integer) dst.putInt(key, (Integer) value);
        else if (value instanceof Boolean) dst.putBoolean(key, (Boolean) value);
        else if (value instanceof Float) dst.putFloat(key, (Float) value);
        else if (value instanceof Long) dst.putLong(key, (Long) value);
        else if (value instanceof Double) dst.putDouble(key, (Double) value);
        else if (value instanceof Short) dst.putShort(key, (Short) value);
        else if (value instanceof Byte) dst.putByte(key, (Byte) value);
        else return false;
        return true;
    }

    @Override
    public void systemReady() {
        // Startup and lazy indexing now share ONE pass over the package map (scan -> repair ->
        // prepare -> index). Previously startup did scan + repair + full rebuild = three full
        // passes, all serialized behind the package map lock.
        ensureComponentIndex();
    }

    /**
     * Build the component resolver index on demand.
     *
     * BlackBoxSystem.startup() (and therefore systemReady()) is only invoked from
     * SystemCallProvider.onCreate(), which is declared in the :black process. APK containers
     * may never instantiate that provider, so the resolver index stays empty: getPackageInfo()
     * still reports 58 activities but getActivityInfo()/resolveActivity() return null. Building
     * the index on the first query removes that dependency entirely.
     */
    void ensureComponentIndex() {
        if (mComponentIndexBuilt) return;
        synchronized (mPackages) {
            if (mComponentIndexBuilt) return;
            // Exactly one scan per process: mComponentIndexBuilt guards this method, so neither
            // systemReady() nor the lazy query path can trigger a redundant second scan.
            try {
                mSettings.scanPackage();
            } catch (Throwable t) {
                Slog.w(TAG, "scanPackage failed: " + t);
            }
            int failed = 0;
            int activities = 0;
            String firstError = null;
            for (BPackageSettings ps : mPackages.values()) {
                try {
                    if (ps == null || ps.pkg == null) continue;
                    // Repair legacy/empty-component records in the same pass, isolated per
                    // package so one malformed record cannot abort the whole index build.
                    ensureComponents(ps);
                    ensureAppMetaData(ps);
                    prepareForIndexing(ps.pkg);
                    mComponentResolver.removeAllComponents(ps.pkg);
                    mComponentResolver.addAllComponents(ps.pkg);
                    activities += ps.pkg.activities.size();
                } catch (Throwable t) {
                    failed++;
                    if (firstError == null) firstError = packageLabel(ps) + ": " + t;
                    Slog.e(TAG, "index rebuild failed for " + packageLabel(ps) + ": " + t);
                }
            }
            mComponentIndexBuilt = true;
            BlackBoxCore.recordIndexRebuild(failed == 0
                    ? "LAZY OK packages=" + mPackages.size() + " activities=" + activities
                    : "LAZY FAILED " + failed + " first=" + firstError);
            Slog.d(TAG, "component index built: activities=" + activities + " failed=" + failed);
        }
    }

    private static String packageLabel(BPackageSettings ps) {
        return ps == null || ps.pkg == null ? "?" : String.valueOf(ps.pkg.packageName);
    }

    /**
     * The component resolver dereferences applicationInfo.processName, component.owner and
     * component.intents. Recovered AXML components always set these, but records restored from
     * an old package.conf may not, so normalise them before indexing.
     */
    private void prepareForIndexing(BPackage pkg) {
        if (pkg.applicationInfo == null) {
            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = pkg.packageName;
            ai.processName = pkg.packageName;
            ai.sourceDir = pkg.baseCodePath;
            ai.publicSourceDir = pkg.baseCodePath;
            pkg.applicationInfo = ai;
        }
        ApplicationInfo ai = pkg.applicationInfo;
        if (ai.packageName == null) ai.packageName = pkg.packageName;
        if (ai.processName == null || ai.processName.isEmpty()) ai.processName = pkg.packageName;
        sanitizeComponents(pkg.activities, pkg);
        sanitizeComponents(pkg.receivers, pkg);
        sanitizeComponents(pkg.services, pkg);
        sanitizeComponents(pkg.providers, pkg);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void sanitizeComponents(java.util.List components, BPackage owner) {
        if (components == null) return;
        for (int i = components.size() - 1; i >= 0; i--) {
            Object o = components.get(i);
            if (!(o instanceof BPackage.Component)) {
                components.remove(i);
                continue;
            }
            BPackage.Component c = (BPackage.Component) o;
            boolean hasInfo;
            if (c instanceof BPackage.Activity) {
                hasInfo = ((BPackage.Activity) c).info != null;
            } else if (c instanceof BPackage.Service) {
                hasInfo = ((BPackage.Service) c).info != null;
            } else if (c instanceof BPackage.Provider) {
                hasInfo = ((BPackage.Provider) c).info != null;
            } else {
                hasInfo = true;
            }
            if (c.className == null || !hasInfo) {
                components.remove(i);
                continue;
            }
            if (c.owner == null) c.owner = owner;
            if (c.intents == null) c.intents = new ArrayList(0);
        }
    }
}