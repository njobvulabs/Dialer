package top.niunaijun.blackbox.utils;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.util.TimeZone;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Cross-process, on-disk diagnostics for virtual app startup.
 *
 * The virtual framework runs the stub activities in separate processes (:p0 .. :p49) and the
 * package service in :black. Static in-memory state is therefore invisible to the UI process,
 * and on restricted containers (HarmonyOS / APK containers) logcat is unavailable, so a crash
 * in a stub process used to be a complete black box: a white window followed by a silent exit.
 *
 * All processes of one APK share the same private files directory, so writing here is the only
 * reliable channel between the stub and the host UI. Everything in this class is best-effort:
 * a diagnostics failure must never be the cause of a crash.
 *
 * Performance note: trace()/stage() used to open+write+close a file on the calling thread for
 * every single line (30+ synchronous IOs per launch, on the main/Binder threads, serialized by
 * a global lock). They now only append to an in-memory buffer; a single background HandlerThread
 * batches the lines into one append every ~120ms. crash() performs a synchronous flush first,
 * so a dying process still leaves a complete, readable report on screen.
 */
public final class BBDiag {
    public static final String TAG = "BBDiag";
    private static final String DIR = "bb_diag";
    private static final String TRACE = "trace.log";
    private static final String STAGE = "stage.txt";
    private static final String CRASH = "crash.txt";
    private static final long MAX_TRACE = 256 * 1024;
    private static final int FLUSH_BYTES = 4096;
    private static final long FLUSH_DELAY_MS = 120L;

    private static volatile Context sContext;
    private static volatile File sDir;

    /** Guards only the in-memory buffer. File IO always happens outside this lock. */
    private static final Object BUF_LOCK = new Object();
    private static final StringBuilder sPending = new StringBuilder(FLUSH_BYTES);
    private static volatile String sPendingStage;
    private static volatile boolean sStageDirty;

    private static volatile Handler sWriter;
    private static volatile boolean sFlushScheduled;
    private static final Object WRITER_LOCK = new Object();
    private static final Runnable FLUSH_TASK = new Runnable() {
        @Override
        public void run() {
            synchronized (BUF_LOCK) {
                sFlushScheduled = false;
            }
            try {
                flushPending();
            } catch (Throwable ignored) {
            }
        }
    };

    /** Pure-arithmetic timestamp: no SimpleDateFormat, no global synchronization, no Date. */
    private static final long TZ_OFFSET =
            TimeZone.getDefault().getOffset(System.currentTimeMillis());

    private BBDiag() {
    }

    private static Context context() {
        Context ctx = sContext;
        if (ctx == null) {
            try {
                ctx = BlackBoxCore.getContext();
            } catch (Throwable ignored) {
            }
        }
        return ctx;
    }

    private static File dir() {
        File d = sDir;
        if (d != null) return d;
        Context ctx = context();
        if (ctx == null) return null;
        try {
            File nd = new File(ctx.getFilesDir(), DIR);
            if (!nd.exists()) nd.mkdirs();
            sDir = nd;
            return nd;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String stamp() {
        long t = (System.currentTimeMillis() + TZ_OFFSET) % 86400000L;
        if (t < 0) t += 86400000L;
        int h = (int) (t / 3600000L);
        int m = (int) ((t / 60000L) % 60);
        int s = (int) ((t / 1000L) % 60);
        int ms = (int) (t % 1000L);
        StringBuilder sb = new StringBuilder(12);
        pad2(sb, h);
        sb.append(':');
        pad2(sb, m);
        sb.append(':');
        pad2(sb, s);
        sb.append('.');
        if (ms < 100) sb.append('0');
        if (ms < 10) sb.append('0');
        sb.append(ms);
        return sb.toString();
    }

    private static void pad2(StringBuilder sb, int v) {
        if (v < 10) sb.append('0');
        sb.append(v);
    }

    private static void ensureWriter() {
        Handler h = sWriter;
        if (h != null) return;
        synchronized (WRITER_LOCK) {
            if (sWriter != null) return;
            try {
                HandlerThread ht =
                        new HandlerThread("bb-diag", Process.THREAD_PRIORITY_BACKGROUND);
                ht.start();
                sWriter = new Handler(ht.getLooper());
            } catch (Throwable ignored) {
            }
        }
    }

    private static void scheduleFlush(boolean immediate) {
        Handler h = sWriter;
        if (h == null) return;
        synchronized (BUF_LOCK) {
            if (immediate || sPending.length() >= FLUSH_BYTES) {
                h.removeCallbacks(FLUSH_TASK);
                sFlushScheduled = false;
                h.post(FLUSH_TASK);
            } else if (!sFlushScheduled) {
                sFlushScheduled = true;
                h.postDelayed(FLUSH_TASK, FLUSH_DELAY_MS);
            }
        }
    }

    /** Swap the buffer out under a short lock, then do all file IO lock-free. */
    private static void flushPending() {
        String chunk;
        String stageLine;
        synchronized (BUF_LOCK) {
            if (sPending.length() == 0) {
                chunk = null;
            } else {
                chunk = sPending.toString();
                sPending.setLength(0);
            }
            stageLine = sStageDirty ? sPendingStage : null;
            sStageDirty = false;
        }
        File d = dir();
        if (d == null) return;
        if (chunk != null && !chunk.isEmpty()) appendTrace(d, chunk);
        if (stageLine != null) writeFile(new File(d, STAGE), stageLine, false);
    }

    private static void appendTrace(File d, String chunk) {
        File f = new File(d, TRACE);
        try {
            byte[] data = chunk.getBytes("UTF-8");
            long len = f.length();
            if (len + data.length > MAX_TRACE) {
                // Keep the most recent half plus this batch. Runs on the background thread only.
                byte[] tail = readTailBytes(f, (int) Math.min(len, MAX_TRACE / 2));
                try (FileOutputStream os = new FileOutputStream(f, false)) {
                    if (tail.length > 0) os.write(tail);
                    os.write(data);
                }
            } else {
                try (FileOutputStream os = new FileOutputStream(f, true)) {
                    os.write(data);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeFile(File f, String content, boolean append) {
        try (FileOutputStream os = new FileOutputStream(f, append);
             OutputStreamWriter w = new OutputStreamWriter(os, "UTF-8")) {
            w.write(content);
            w.flush();
        } catch (Throwable ignored) {
        }
    }

    /** Append a timestamped, pid-tagged line to the shared trace log (in-memory, no IO). */
    public static void trace(String tag, String msg) {
        try {
            ensureWriter();
            String line = "[" + stamp() + "][p" + Process.myPid() + "]["
                    + (tag == null ? "-" : tag) + "] " + String.valueOf(msg) + "\n";
            synchronized (BUF_LOCK) {
                sPending.append(line);
            }
            scheduleFlush(false);
        } catch (Throwable ignored) {
        }
    }

    /** Record the "last known stage". Written by the background flusher, or synchronously on crash. */
    public static void stage(String stage) {
        try {
            ensureWriter();
            String value = String.valueOf(stage);
            synchronized (BUF_LOCK) {
                sPendingStage = "[" + stamp() + "][p" + Process.myPid() + "] " + value + "\n";
                sStageDirty = true;
                sPending.append("[").append(stamp()).append("][p").append(Process.myPid())
                        .append("][stage] ").append(value).append("\n");
            }
            scheduleFlush(false);
        } catch (Throwable ignored) {
        }
    }

    /** Persist a full crash report, including the stage and the cause chain. */
    public static void crash(Thread thread, Throwable e) {
        try {
            // A crashing process will die: synchronously drain everything we buffered first so
            // the on-screen report is complete even though the normal async flusher never runs.
            ensureWriter();
            synchronized (BUF_LOCK) {
                sPending.append("[").append(stamp()).append("][p").append(Process.myPid())
                        .append("][crash] ").append(String.valueOf(e)).append("\n");
            }
            flushPending();
        } catch (Throwable ignored) {
        }
        File d = dir();
        if (d == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("=== VIRTUAL PROCESS CRASH ===\n");
        sb.append("time: ").append(stamp()).append("\n");
        sb.append("pid: ").append(Process.myPid()).append("\n");
        sb.append("thread: ").append(thread == null ? "unknown" : thread.getName()).append("\n");
        try {
            sb.append("stage: ").append(readTail(new File(d, STAGE), 512).trim()).append("\n");
        } catch (Throwable ignored) {
        }
        sb.append("throwable: ").append(String.valueOf(e)).append("\n");
        sb.append("=== STACK ===\n");
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            pw.flush();
            sb.append(sw.toString());
            Throwable cause = e == null ? null : e.getCause();
            int depth = 0;
            while (cause != null && depth++ < 8) {
                sb.append("--- Caused by: ").append(cause).append(" ---\n");
                StringWriter cw = new StringWriter();
                PrintWriter cpw = new PrintWriter(cw);
                cause.printStackTrace(cpw);
                cpw.flush();
                sb.append(cw.toString());
                cause = cause.getCause();
            }
        } catch (Throwable t) {
            sb.append("(stack unavailable: ").append(t).append(")\n");
        }
        sb.append("=== END ===\n");
        writeFile(new File(d, CRASH), sb.toString(), false);
    }

    /**
     * Install a global uncaught-exception handler that writes the crash to disk before
     * delegating to the previous handler. Called from Application.attachBaseContext so it is
     * installed in EVERY process of the APK, including the stub processes.
     */
    public static void installCrashHandler(Context ctx) {
        if (ctx != null) {
            sContext = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
            dir();
        }
        try {
            Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
            if (old instanceof DiagCrashHandler) return;
            Thread.setDefaultUncaughtExceptionHandler(new DiagCrashHandler(old));
        } catch (Throwable ignored) {
        }
    }

    private static final class DiagCrashHandler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler mOld;

        DiagCrashHandler(Thread.UncaughtExceptionHandler old) {
            mOld = old;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            crash(t, e);
            if (mOld != null) {
                mOld.uncaughtException(t, e);
            }
        }
    }

    /** Clear the previous crash so the next launch starts with a clean slate. */
    public static void clearCrash() {
        File d = dir();
        if (d == null) return;
        try {
            new File(d, CRASH).delete();
        } catch (Throwable ignored) {
        }
    }

    /** Timestamp of the persisted crash, or 0 when there is none. Used to auto-show it once. */
    public static long lastCrashTime() {
        File d = dir();
        if (d == null) return 0L;
        try {
            File f = new File(d, CRASH);
            return f.exists() ? f.lastModified() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** Short, screenshot-friendly crash report for the automatic post-crash dialog. */
    public static String crashReport() {
        File d = dir();
        if (d == null) return "diagDir=UNAVAILABLE";
        StringBuilder sb = new StringBuilder();
        String crash = readTail(new File(d, CRASH), 6000).trim();
        // crash.txt records the stage that was live when the process died. stage.txt is
        // overwritten by every later launch, so preferring it here mixed a new launch's stage
        // into an older crash report (same file, different pids and packages).
        String stage = stageFromCrash(crash);
        if (stage == null || stage.isEmpty()) {
            stage = readTail(new File(d, STAGE), 512).trim();
        }
        sb.append("lastStage=").append(stage.isEmpty() ? "none" : stage).append("\n\n");
        sb.append(crash.isEmpty() ? "(no crash recorded)" : crash);
        return sb.toString();
    }

    /** Extracts the {@code stage: ...} line written by crash(), so it matches that crash. */
    private static String stageFromCrash(String crash) {
        if (crash == null) return null;
        for (String line : crash.split("\n")) {
            if (line.startsWith("stage: ")) return line.substring(7).trim();
        }
        return null;
    }

    /** A compact, screenshot-friendly digest appended to on-screen diagnostic reports. */
    public static String digest() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n--- stub process log (shared across processes) ---\n");
        File d = dir();
        if (d == null) {
            sb.append("diagDir=UNAVAILABLE\n");
            return sb.toString();
        }
        sb.append("diagDir=").append(d.getAbsolutePath()).append("\n");
        String stage = readTail(new File(d, STAGE), 512).trim();
        sb.append("lastStage=").append(stage.isEmpty() ? "none" : stage).append("\n");
        String crash = readTail(new File(d, CRASH), 3500).trim();
        sb.append("lastCrash=").append(crash.isEmpty() ? "none" : crash).append("\n");
        String trace = readTail(new File(d, TRACE), 4500).trim();
        sb.append("traceTail=\n").append(trace.isEmpty() ? "(empty)" : trace).append("\n");
        return sb.toString();
    }

    /** Read only the tail bytes via seek() instead of loading the whole 256KB log into memory. */
    private static byte[] readTailBytes(File f, int maxBytes) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            int size = (int) Math.min(len, Math.max(0, maxBytes));
            if (size <= 0) return new byte[0];
            raf.seek(len - size);
            byte[] buf = new byte[size];
            int off = 0;
            while (off < size) {
                int n = raf.read(buf, off, size - off);
                if (n < 0) break;
                off += n;
            }
            return off == size ? buf : java.util.Arrays.copyOf(buf, off);
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    private static String readTail(File f, int maxBytes) {
        if (!f.exists()) return "";
        try {
            return new String(readTailBytes(f, maxBytes), "UTF-8");
        } catch (Throwable t) {
            return "(read error " + t + ")";
        }
    }
}
