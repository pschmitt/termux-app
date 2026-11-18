package dev.pschmitt.termux.shared.termux.nix;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;

/**
 * The full-Nix guest that a provisioning bootstrap ships: {@code $PREFIX/bin/nixpp} with its
 * launcher, bootstrap seed and session manifest in {@code $PREFIX/share/nixpp}. {@code nixpp init}
 * sets up the runtime in {@link #RUNTIME_DIR_PATH}; {@code nixpp login} finishes an interrupted
 * setup and enters the guest.
 */
public final class TermuxNix {

    public static final String NIXPP_PATH = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/nixpp";
    public static final String ASSETS_DIR_PATH = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/share/nixpp";
    /** nixpp's default runtime directory, {@code $HOME/.local/share/termuxpp-nix}. */
    public static final String RUNTIME_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.local/share/termuxpp-nix";

    private static final String[] ASSETS = {"launcher", "bootstrap-aarch64.zip", "manifest.json"};

    private TermuxNix() {}

    /** Whether the prefix can set up a Nix runtime: nixpp and all of its bundled assets are present. */
    public static boolean isProvisioned() {
        return isProvisioned(new File(NIXPP_PATH), new File(ASSETS_DIR_PATH));
    }

    static boolean isProvisioned(@NonNull File nixpp, @NonNull File assets) {
        if (!nixpp.canExecute()) return false;
        for (String asset : ASSETS)
            if (!new File(assets, asset).isFile()) return false;
        return true;
    }

    /** Whether the runtime finished its setup. */
    public static boolean isReady() {
        return new File(RUNTIME_DIR_PATH, "ready").isFile();
    }

    /** Prefer the app configuration in the ready guest's actual home. */
    @NonNull
    public static List<String> configPaths(@NonNull List<String> nativePaths) {
        File guest = guestHome(new File(RUNTIME_DIR_PATH));
        if (guest == null) return nativePaths;
        List<String> paths = new ArrayList<>(nativePaths.size() * 2);
        String nativeHome = TermuxConstants.TERMUX_HOME_DIR_PATH + "/";
        try {
            String guestPrefix = guest.getCanonicalPath() + "/";
            for (String path : nativePaths) {
                if (!path.startsWith(nativeHome)) return nativePaths;
                File candidate = new File(guest, path.substring(nativeHome.length()));
                if (candidate.getCanonicalPath().startsWith(guestPrefix)) paths.add(candidate.getPath());
                else return nativePaths;
            }
        } catch (IOException e) {
            return nativePaths;
        }
        paths.addAll(nativePaths);
        return paths;
    }

    @Nullable
    static File guestHome(@NonNull File runtime) {
        File session = new File(runtime, "session.env");
        if (!new File(runtime, "ready").isFile() || !session.isFile() || session.length() > 16384)
            return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(session))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("SESSION_HOME=")) continue;
                String home = line.substring("SESSION_HOME=".length());
                if (!home.matches("/home/[a-z_][a-z0-9_-]*")) return null;
                File homes = new File(runtime, "root/home");
                File guest = new File(homes, home.substring("/home/".length()));
                if (guest.isDirectory() && guest.getCanonicalFile().getParentFile().equals(homes.getCanonicalFile()))
                    return guest;
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /** Whether new default sessions enter the guest: provisioned and not opted out with {@code nix-session=false}. */
    public static boolean shouldEnterGuest(@Nullable TermuxAppSharedProperties properties) {
        return isProvisioned() && (properties == null || properties.shouldStartNixSession());
    }

    /** The guest user name for a new runtime ({@code nix-user}). */
    @NonNull
    public static String user(@Nullable TermuxAppSharedProperties properties) {
        String user = properties == null ? null : properties.getNixUser();
        return user == null ? TermuxPropertyConstants.DEFAULT_IVALUE_NIX_USER : user;
    }

    /** The arguments after {@link #NIXPP_PATH} of a session that enters the guest. */
    @NonNull
    public static String[] loginArguments(@NonNull String user) {
        return new String[]{"login", "--user", user};
    }

    /** The arguments after {@link #NIXPP_PATH} that set up the runtime with JSON progress. */
    @NonNull
    public static String[] initArguments(@NonNull String user) {
        return new String[]{"--json", "init", "--user", user};
    }
}
