package com.termux.app.update;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * Installs the downloaded update as the shell user through Shizuku (or Sui). The package installer
 * is reached the way Obtainium and InstallerX do it: the system's {@code package} binder, wrapped
 * in a {@link ShizukuBinderWrapper} so calls run with Shizuku's uid, then {@code IPackageInstaller}
 * and its session binder wrapped the same way. Those are hidden APIs, used through reflection after
 * lifting the hidden-API restrictions; this class is the only place that knows about them. Nothing
 * here runs a shell command, and the only input is a file the controller already verified.
 */
public final class ShizukuInstaller {

    private static final String LOG_TAG = "ShizukuInstaller";
    public static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";
    static final int REQUEST_CODE = 0x5112;
    private static final int SHELL_UID = 2000;
    /** Hidden {@code SessionParams.installFlags}: lets a same-version build replace itself. */
    private static final int INSTALL_REPLACE_EXISTING = 0x00000002;

    /** Set when a permission request was answered with "deny"; cleared by a grant. */
    private static volatile boolean deniedByUser = false;

    private ShizukuInstaller() {}

    /** Called by the permission result listener the UI registers. */
    public static void onPermissionResult(int grantResult) {
        deniedByUser = grantResult != PackageManager.PERMISSION_GRANTED;
    }

    @NonNull
    public static ShizukuState state(@NonNull Context context) {
        boolean alive = false;
        boolean granted = false;
        boolean denied = false;
        boolean supported = true;
        // Shizuku itself needs Android 7 here (its API library has minSdk 24); its libraries are only ever touched from there on.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return ShizukuState.UNAVAILABLE;
        try {
            alive = Shizuku.pingBinder();
            if (alive) {
                supported = !Shizuku.isPreV11();
                granted = supported && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                if (granted) deniedByUser = false;
                // "Deny and don't ask again" shows up as a rationale request; a plain deny as our flag.
                denied = supported && !granted && (deniedByUser || Shizuku.shouldShowRequestPermissionRationale());
            }
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not query Shizuku", t instanceof Exception ? (Exception) t : new RuntimeException(t));
            alive = false;
            granted = false;
        }
        return ShizukuState.of(isManagerInstalled(context), alive, granted, supported, denied);
    }

    /** Asks Shizuku for permission; the answer arrives at the registered listener. */
    public static void requestPermission() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "Shizuku permission request failed: " + t);
        }
    }

    /** Opens the Shizuku app, or null when it is not installed. */
    @Nullable
    public static Intent managerLaunchIntent(@NonNull Context context) {
        return context.getPackageManager().getLaunchIntentForPackage(MANAGER_PACKAGE);
    }

    private static boolean isManagerInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(MANAGER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Commits {@code file} in a privileged install session for the package of {@code target}, which
     * is a fixed allowlist entry (never a name taken from the network); the verdict goes to
     * {@code resultSender}. Throws when Shizuku is not ready or anything fails; the session is
     * abandoned then.
     */
    public static void install(@NonNull Context context, @NonNull UpdateTarget target, @NonNull File file,
                               @NonNull IntentSender resultSender) throws Exception {
        if (state(context) != ShizukuState.READY) throw new IllegalStateException("Shizuku is not ready");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("");

        Object installerInterface = asInterface("android.content.pm.IPackageInstaller", installerBinder());
        PackageInstaller packageInstaller = newPackageInstaller(installerInterface);

        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(target.packageName);
        params.setSize(file.length());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        addInstallFlags(params, INSTALL_REPLACE_EXISTING);

        int sessionId = packageInstaller.createSession(params);
        try {
            // IPackageInstaller.openSession(id) -> IPackageInstallerSession; the session only
            // answers its owner (shell), so its binder is wrapped as well.
            Object sessionInterface = call(installerInterface, "openSession", sessionId);
            IBinder sessionBinder = new ShizukuBinderWrapper(((IInterface) sessionInterface).asBinder());
            PackageInstaller.Session session = newSession(
                    asInterface("android.content.pm.IPackageInstallerSession", sessionBinder));
            try {
                try (InputStream in = new FileInputStream(file);
                     OutputStream out = session.openWrite("update.apk", 0, file.length())) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
                    session.fsync(out);
                }
                session.commit(resultSender);
            } finally {
                session.close();
            }
        } catch (Throwable t) {
            try {
                packageInstaller.abandonSession(sessionId);
            } catch (Throwable ignored) {
                // Nothing left to clean up.
            }
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }
    }

    /** {@code IPackageManager.getPackageInstaller()} over Shizuku's uid; returns the installer binder. */
    private static IBinder installerBinder() throws Exception {
        IBinder packageBinder = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package"));
        Object packageManager = asInterface("android.content.pm.IPackageManager", packageBinder);
        IBinder installer = ((IInterface) call(packageManager, "getPackageInstaller")).asBinder();
        return new ShizukuBinderWrapper(installer);
    }

    private static void addInstallFlags(PackageInstaller.SessionParams params, int flags) {
        try {
            Field field = PackageInstaller.SessionParams.class.getDeclaredField("installFlags");
            field.setAccessible(true);
            field.setInt(params, field.getInt(params) | flags);
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "Could not set install flags: " + t);
        }
    }

    /** {@code PackageInstaller(IPackageInstaller, installerPackageName, [attributionTag,] userId)}. */
    private static PackageInstaller newPackageInstaller(Object installerInterface) throws Exception {
        Class<?> iface = Class.forName("android.content.pm.IPackageInstaller");
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : PackageInstaller.class.getDeclaredConstructors()) {
            Class<?>[] types = candidate.getParameterTypes();
            if (types.length > 0 && types[0] == iface) {
                constructor = candidate;
                break;
            }
        }
        if (constructor == null) throw new NoSuchMethodException("PackageInstaller constructor");
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        int strings = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == iface) {
                args[i] = installerInterface;
            } else if (types[i] == String.class) {
                args[i] = strings++ == 0 ? installerName() : null;
            } else if (types[i] == int.class) {
                args[i] = Process.myUserHandle().hashCode();
            } else {
                throw new IllegalStateException("Unexpected PackageInstaller constructor parameter " + types[i]);
            }
        }
        constructor.setAccessible(true);
        return (PackageInstaller) newInstance(constructor, args);
    }

    private static PackageInstaller.Session newSession(Object sessionInterface) throws Exception {
        Class<?> iface = Class.forName("android.content.pm.IPackageInstallerSession");
        Constructor<PackageInstaller.Session> constructor =
                PackageInstaller.Session.class.getDeclaredConstructor(iface);
        constructor.setAccessible(true);
        return (PackageInstaller.Session) newInstance(constructor, sessionInterface);
    }

    /** The installer package the session is recorded under: the shell (or root) Shizuku runs as. */
    private static String installerName() {
        int uid;
        try {
            uid = Shizuku.getUid();
        } catch (Throwable t) {
            uid = SHELL_UID;
        }
        return uid == 0 ? "root" : "com.android.shell";
    }

    private static Object asInterface(String className, IBinder binder) throws Exception {
        Class<?> stub = Class.forName(className + "$Stub");
        Method asInterface = stub.getMethod("asInterface", IBinder.class);
        return invoke(asInterface, null, binder);
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == args.length) {
                return invoke(method, target, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        }
    }

    private static Object newInstance(Constructor<?> constructor, Object... args) throws Exception {
        try {
            return constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        }
    }

    private static Exception unwrap(InvocationTargetException e) {
        Throwable target = e.getTargetException();
        return target instanceof Exception ? (Exception) target : e;
    }

}
