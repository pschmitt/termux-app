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
 * Exact stock to fork identity rewrites for the {@code termux-tools} package: the helper scripts, the
 * {@code bin/login} script and the {@code etc/termux-login.sh} conffile.
 *
 * <p>The stock files address the stock package ({@code com.termux}), which silently misses a fork
 * with another package name, and lack the native pkg-redirect preload of the fork. The canonical table
 * lives in {@code termuxpp/helper-rewrites.tsv}. It is applied while the bootstrap is extracted and
 * rendered next to {@code adapt-helpers}, the APT post-invoke hook that re-applies it after APT
 * replaced the files with the stock versions. Rules are literal strings keyed by an exact file name,
 * never patterns.
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
    /** The files that named rules may address. */
    private static final Pattern ADAPTED_FILE = Pattern.compile("bin/termux-[a-z-]+|bin/login|etc/termux-login\\.sh");
    private static final Pattern SAFE_PREFIX = Pattern.compile("/[A-Za-z0-9._/-]+");

    enum Kind { IDENTITY, RULE, APPEND, REQUIRE, FORBID }

    /**
     * One table row. {@code stock} is the text to replace (IDENTITY, RULE), the marker (APPEND) or the
     * text to check (REQUIRE, FORBID); {@code fork} is the replacement or the text to append. IDENTITY
     * rows have no file: they apply to every text file.
     */
    static final class Rule {
        final Kind kind;
        final String file;
        final String stock;
        final String fork;

        Rule(Kind kind, String file, String stock, String fork) {
            this.kind = kind;
            this.file = file;
            this.stock = stock;
            this.fork = fork;
        }

        Rule(String file, String stock, String fork) {
            this(Kind.RULE, file, stock, fork);
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
     * Parse a rendered table. Malformed rows, rows for files outside the whitelist, rows with an
     * unresolved placeholder and rows that would change nothing (or would not be idempotent) are
     * dropped, so an incomplete identity fails safe by leaving the files alone.
     */
    @NonNull
    static List<Rule> parse(@NonNull String renderedTable) {
        List<Rule> rules = new ArrayList<>();
        for (String line : renderedTable.split("\n", -1)) {
            String[] fields = line.split("\t", -1);
            switch (fields[0]) {
                case "identity":
                    if (fields.length != 3 || !usable(fields[1], fields[2], true)) continue;
                    rules.add(new Rule(Kind.IDENTITY, "", fields[1], fields[2]));
                    break;
                case "rule":
                    if (fields.length != 4 || !ADAPTED_FILE.matcher(fields[1]).matches()
                        || !usable(fields[2], fields[3], true)) continue;
                    rules.add(new Rule(Kind.RULE, fields[1], fields[2], fields[3]));
                    break;
                case "append":
                    if (fields.length != 4 || !ADAPTED_FILE.matcher(fields[1]).matches()
                        || !usable(fields[2], fields[3], false)) continue;
                    rules.add(new Rule(Kind.APPEND, fields[1], fields[2], fields[3]));
                    break;
                case "require":
                case "forbid":
                    // The fourth field is the guard: it only resolves for the fork identity.
                    if (fields.length != 4 || !ADAPTED_FILE.matcher(fields[1]).matches()
                        || !usable(fields[2], fields[3], false)) continue;
                    rules.add(new Rule("require".equals(fields[0]) ? Kind.REQUIRE : Kind.FORBID,
                        fields[1], fields[2], ""));
                    break;
                default:
                    break;
            }
        }
        return Collections.unmodifiableList(rules);
    }

    private static boolean usable(String stock, String fork, boolean mustChangeAndBeIdempotent) {
        if (stock.isEmpty() || fork.isEmpty()) return false;
        if (UNRESOLVED_PLACEHOLDER.matcher(stock).find() || UNRESOLVED_PLACEHOLDER.matcher(fork).find()) return false;
        return !mustChangeAndBeIdempotent || (!stock.equals(fork) && !fork.contains(stock));
    }

    /**
     * Apply the rows for {@code file} (for example {@code bin/termux-open}) to a file's text: the
     * identity rows, then the file's rules, then its appends.
     */
    @NonNull
    static String apply(@NonNull List<Rule> rules, String file, @NonNull String text) {
        if (file == null) return text;
        String result = text;
        for (Rule rule : rules) {
            if (rule.kind == Kind.IDENTITY && result.contains(rule.stock)) {
                result = result.replace(rule.stock, rule.fork);
            }
        }
        for (Rule rule : rules) {
            if (rule.kind == Kind.RULE && rule.file.equals(file) && result.contains(rule.stock)) {
                result = result.replace(rule.stock, rule.fork);
            }
        }
        for (Rule rule : rules) {
            if (rule.kind == Kind.APPEND && rule.file.equals(file) && !result.contains(rule.stock)) {
                result = result + rule.fork.replace("\\n", "\n");
            }
        }
        return result;
    }

    /** The problems of an adapted file: required text missing or forbidden text still present. */
    @NonNull
    static List<String> verify(@NonNull List<Rule> rules, String file, @NonNull String adapted) {
        List<String> problems = new ArrayList<>();
        if (file == null) return problems;
        for (Rule rule : rules) {
            if (!file.equals(rule.file)) continue;
            if (rule.kind == Kind.REQUIRE && !adapted.contains(rule.stock)) {
                problems.add(file + ": missing expected text: " + rule.stock);
            } else if (rule.kind == Kind.FORBID && adapted.contains(rule.stock)) {
                problems.add(file + ": still contains stock text: " + rule.stock);
            }
        }
        return problems;
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
        return "// Managed by TermuxPP: re-apply the fork identity to the termux-tools files after dpkg ran.\n"
            + "// The hook only touches files of termux-tools. It fails, and so makes apt report an error, when it\n"
            + "// cannot restore the fork identity, because a silently broken login would lock the user out.\n"
            + "DPkg::Post-Invoke { \"" + prefixPath + "/bin/bash " + prefixPath + "/" + HOOK_SCRIPT + "\"; };\n";
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
