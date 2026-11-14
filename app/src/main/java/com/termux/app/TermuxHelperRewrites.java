package com.termux.app;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Exact stock to fork identity rewrites for the {@code termux-tools} helper scripts.
 *
 * <p>The stock helpers address the stock package ({@code com.termux}), which silently misses a fork
 * with another package name. The canonical table lives in {@code termuxpp/helper-rewrites.tsv}. It is
 * applied to the helpers while the bootstrap is extracted and rendered next to {@code adapt-helpers},
 * the APT post-invoke hook that re-applies it after APT replaced the helpers with the stock versions.
 * Rules are literal strings keyed by an exact helper name, never patterns.
 */
final class TermuxHelperRewrites {

    static final String TABLE_RESOURCE = "/termuxpp/helper-rewrites.tsv";
    static final String HOOK_SCRIPT_RESOURCE = "/termuxpp/adapt-helpers.sh";

    /** Where the hook files live, relative to the prefix. */
    static final String HOOK_DIR = "libexec/termuxpp";
    static final String HOOK_SCRIPT = HOOK_DIR + "/adapt-helpers";
    static final String HOOK_TABLE = HOOK_DIR + "/helper-rewrites.tsv";
    static final String APT_CONFIG = "etc/apt/apt.conf.d/99termuxpp-helpers";

    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("@[A-Z_]+@");
    private static final Pattern HELPER_NAME = Pattern.compile("bin/termux-[a-z-]+");
    private static final Pattern SAFE_PREFIX = Pattern.compile("/[A-Za-z0-9._/-]+");

    static final class Rule {
        final String file;
        final String stock;
        final String fork;

        Rule(String file, String stock, String fork) {
            this.file = file;
            this.stock = stock;
            this.fork = fork;
        }
    }

    private TermuxHelperRewrites() {}

    /** Fill {@code @KEY@} placeholders. Keys are given without the surrounding {@code @}. */
    @NonNull
    static String render(@NonNull String template, @NonNull Map<String, String> values) {
        String rendered = template;
        for (Map.Entry<String, String> value : values.entrySet()) {
            // An empty value stays a placeholder so that parse() drops the rule instead of writing garbage.
            if (value.getValue() == null || value.getValue().isEmpty()) continue;
            rendered = rendered.replace("@" + value.getKey() + "@", value.getValue());
        }
        return rendered;
    }

    /**
     * Parse a rendered table. Malformed rules, rules for helpers outside the whitelist, rules with an
     * unresolved placeholder and rules that would change nothing are dropped, so an incomplete
     * identity fails safe by leaving the helpers alone.
     */
    @NonNull
    static List<Rule> parse(@NonNull String renderedTable) {
        List<Rule> rules = new ArrayList<>();
        for (String line : renderedTable.split("\n", -1)) {
            if (!line.startsWith("rule\t")) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 4) continue;
            String file = fields[1];
            String stock = fields[2];
            String fork = fields[3];
            if (!HELPER_NAME.matcher(file).matches()) continue;
            if (stock.isEmpty() || fork.isEmpty() || stock.equals(fork)) continue;
            if (UNRESOLVED_PLACEHOLDER.matcher(fork).find()) continue;
            rules.add(new Rule(file, stock, fork));
        }
        return Collections.unmodifiableList(rules);
    }

    /** Apply the rules for {@code file} (for example {@code bin/termux-open}) to a helper's text. */
    @NonNull
    static String apply(@NonNull List<Rule> rules, String file, @NonNull String text) {
        if (file == null) return text;
        String result = text;
        for (Rule rule : rules) {
            if (rule.file.equals(file) && result.contains(rule.stock)) {
                result = result.replace(rule.stock, rule.fork);
            }
        }
        return result;
    }

    /** The table written next to the hook script: the prefix it may patch, then the rendered rules. */
    @NonNull
    static String hookTable(@NonNull String prefixPath, @NonNull String renderedTemplate) {
        return "prefix\t" + prefixPath + "\n" + renderedTemplate;
    }

    /** The hook script with a shebang that works on Android, where there is no {@code /usr/bin/env}. */
    @NonNull
    static String hookScript(@NonNull String prefixPath, @NonNull String scriptTemplate) {
        int firstLineEnd = scriptTemplate.indexOf('\n');
        return "#!" + prefixPath + "/bin/bash" + (firstLineEnd < 0 ? "\n" : scriptTemplate.substring(firstLineEnd));
    }

    @NonNull
    static String aptConfig(@NonNull String prefixPath) {
        return "// Managed by TermuxPP: re-apply the fork identity to the termux-tools helpers after dpkg ran.\n"
            + "// The hook never fails apt and never touches anything but the helpers in helper-rewrites.tsv.\n"
            + "DPkg::Post-Invoke { \"" + prefixPath + "/bin/bash " + prefixPath + "/" + HOOK_SCRIPT + " || true\"; };\n";
    }

    /** True when the prefix path can be embedded in the APT configuration and the table unambiguously. */
    static boolean isSafePrefix(String prefixPath) {
        return prefixPath != null && SAFE_PREFIX.matcher(prefixPath).matches() && !prefixPath.contains("..");
    }

    /**
     * Write the hook, its table and the APT configuration under {@code stagingPrefixDir}. The files
     * refer to {@code prefixPath}, the final location of the prefix.
     */
    static void deployHook(@NonNull File stagingPrefixDir, @NonNull String prefixPath, @NonNull Map<String, String> values)
        throws IOException {
        if (!isSafePrefix(prefixPath)) {
            throw new IOException("Unsafe prefix path for the helper hook: " + prefixPath);
        }
        String renderedTable = render(readResource(TABLE_RESOURCE), values);
        writeFile(new File(stagingPrefixDir, HOOK_SCRIPT),
            hookScript(prefixPath, readResource(HOOK_SCRIPT_RESOURCE)), true);
        writeFile(new File(stagingPrefixDir, HOOK_TABLE), hookTable(prefixPath, renderedTable), false);
        writeFile(new File(stagingPrefixDir, APT_CONFIG), aptConfig(prefixPath), false);
    }

    private static void writeFile(File file, String content, boolean executable) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory " + parent);
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        // Owner-only: no access for anybody else, executable only when asked for.
        boolean ok = file.setReadable(false, false) && file.setWritable(false, false)
            && file.setExecutable(false, false)
            && file.setReadable(true, true) && file.setWritable(true, true)
            && file.setExecutable(executable, true);
        if (!ok) throw new IOException("Cannot set permissions on " + file);
    }

    @NonNull
    static String readResource(@NonNull String name) throws IOException {
        try (InputStream in = TermuxHelperRewrites.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("Missing resource " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
