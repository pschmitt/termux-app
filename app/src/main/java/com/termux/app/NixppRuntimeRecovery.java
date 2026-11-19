package com.termux.app;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;

import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.nix.TermuxNix;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Advertise the installed APK to nixpp and restore an enabled controller. */
public final class NixppRuntimeRecovery {
    private static final String LOG_TAG = "NixppRuntimeRecovery";
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean();

    private static final long WATCHDOG_MS = 30_000;
    private static final long WATCHDOG_MAX_BACKOFF_MS = 5 * 60_000;
    private static HandlerThread watchdogThread;
    private static Handler watchdogHandler;
    private static int failedRestarts;

    /**
     * Restart the native controller whenever its socket stops answering. Android's phantom
     * process killer and APK updates take it down while the app keeps running, and nothing else
     * would bring it back until the app process restarts. The launcher is idempotent: a second
     * controller exits when one already serves the runtime.
     */
    public static synchronized void startWatchdog(Context context) {
        if (watchdogThread != null) return;
        Context application = context.getApplicationContext();
        watchdogThread = new HandlerThread("nixpp-controller-watchdog");
        watchdogThread.start();
        watchdogHandler = new Handler(watchdogThread.getLooper());
        watchdogHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                long delay = WATCHDOG_MS;
                File runtime = new File(TermuxNix.RUNTIME_DIR_PATH);
                if (new File(runtime, "system.enabled").isFile() && !controllerAnswers(runtime)) {
                    failedRestarts++;
                    Logger.logWarn(LOG_TAG, "The nixpp controller does not answer; restarting it (attempt " + failedRestarts + ")");
                    SCHEDULED.set(false);
                    schedule(application);
                    delay = Math.min(WATCHDOG_MS << Math.min(failedRestarts - 1, 4), WATCHDOG_MAX_BACKOFF_MS);
                } else {
                    failedRestarts = 0;
                }
                watchdogHandler.postDelayed(this, delay);
            }
        }, WATCHDOG_MS);
    }

    public static synchronized void stopWatchdog() {
        if (watchdogThread == null) return;
        watchdogHandler.removeCallbacksAndMessages(null);
        watchdogThread.quitSafely();
        watchdogThread = null;
        watchdogHandler = null;
    }

    /** Whether the controller accepts connections on its socket ($SESSION_TMP_DIR/nixpp.sock). */
    static boolean controllerAnswers(File runtime) {
        File socket = new File(sessionTmpDir(runtime), "nixpp.sock");
        if (!socket.exists()) return false;
        try (LocalSocket client = new LocalSocket()) {
            client.connect(new LocalSocketAddress(socket.getPath(), LocalSocketAddress.Namespace.FILESYSTEM));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static File sessionTmpDir(File runtime) {
        File env = new File(runtime, "session.env");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(env), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("export ")) line = line.substring(7);
                if (line.startsWith("SESSION_TMP_DIR=")) {
                    String value = line.substring("SESSION_TMP_DIR=".length());
                    if (value.length() >= 2 && (value.startsWith("'") || value.startsWith("\""))) value = value.substring(1, value.length() - 1);
                    if (!value.isEmpty()) return new File(value);
                }
            }
        } catch (IOException ignored) {
        }
        return new File(runtime, "root/tmp");
    }

    public static void schedule(Context context) {
        File runtime = new File(TermuxNix.RUNTIME_DIR_PATH);
        File launcher = new File(runtime, "start-system-control.sh");
        File library = new File(context.getApplicationInfo().nativeLibraryDir,
            "libtermux-pkg-redirect.so");
        if (!new File(runtime, "ready").isFile() ||
            !new File(runtime, "system.enabled").isFile() ||
            !launcher.isFile() || !library.isFile() ||
            !SCHEDULED.compareAndSet(false, true)) return;

        String dataDir = context.getApplicationInfo().dataDir;
        new Thread(() -> {
            try {
                publishLibrary(runtime, library);
                ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c",
                    "exec /system/bin/sh \"$1\" </dev/null >>\"$2\" 2>&1",
                    "nixpp", launcher.getPath(), new File(runtime, "system-control.log").getPath());
                builder.environment().remove("LD_PRELOAD");
                builder.environment().remove("LD_LIBRARY_PATH");
                builder.environment().put("TERMUX_APP__DATA_DIR", dataDir);
                builder.start();
            } catch (IOException exception) {
                SCHEDULED.set(false);
                Logger.logWarn(LOG_TAG, "Could not start nixpp runtime recovery: " + exception.getMessage());
            }
        }, "nixpp-runtime-recovery").start();
    }

    private static void publishLibrary(File runtime, File library) throws IOException {
        File temporary = File.createTempFile("apk-library.", ".tmp", runtime);
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                stream.write((library.getCanonicalPath() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!temporary.renameTo(new File(runtime, "apk-library"))) {
                throw new IOException("could not publish the native library path");
            }
        } finally {
            temporary.delete();
        }
    }

    private NixppRuntimeRecovery() {}
}
