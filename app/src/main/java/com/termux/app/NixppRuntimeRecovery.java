package com.termux.app;

import android.content.Context;

import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.termux.nix.TermuxNix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Advertise the installed APK to nixpp and restore an enabled controller. */
public final class NixppRuntimeRecovery {
    private static final String LOG_TAG = "NixppRuntimeRecovery";
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean();

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
