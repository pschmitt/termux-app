package com.termux.app;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TermuxHelperRewritesTest {

    static final String[] HELPERS = {
        "termux-info", "termux-open", "termux-reload-settings", "termux-reset",
        "termux-setup-storage", "termux-wake-lock", "termux-wake-unlock",
    };

    /** Stock identity values: the rewrites must be no-ops. */
    static Map<String, String> stockValues() {
        Map<String, String> values = new HashMap<>();
        values.put("PACKAGE", "com.termux");
        values.put("RELOAD_STYLE_ACTION", "com.termux.app.reload_style");
        values.put("WAKE_LOCK_ACTION", "com.termux.service_wake_lock");
        values.put("WAKE_UNLOCK_ACTION", "com.termux.service_wake_unlock");
        values.put("DATA_DIR", "/data/data/com.termux");
        // No redirect library and no fork guard for the stock identity.
        return values;
    }

    static Map<String, String> forkValues() {
        Map<String, String> values = new HashMap<>();
        values.put("PACKAGE", "dev.pschmitt.termux");
        values.put("RELOAD_STYLE_ACTION", "dev.pschmitt.termux.app.reload_style");
        values.put("WAKE_LOCK_ACTION", "dev.pschmitt.termux.service_wake_lock");
        values.put("WAKE_UNLOCK_ACTION", "dev.pschmitt.termux.service_wake_unlock");
        values.put("DATA_DIR", "/data/data/dev.pschmitt.termux");
        values.put("REDIRECT_SO_VAR", "TERMUX_PKG_REDIRECT_SO");
        values.put("FORK_ONLY", "fork");
        return values;
    }

    /** Other real stock termux-tools files used as fixtures, by path relative to the prefix. */
    static final String[] STOCK_FILES = {"bin/login", "etc/termux-login.sh", "bin/pkg"};

    static String stockHelper(String name) throws IOException {
        return TermuxHelperRewrites.readResource("/termuxpp/stock/" + name);
    }

    /** A real stock file of termux-tools by its path relative to the prefix, such as bin/login. */
    static String stockFile(String relativePath) throws IOException {
        return stockHelper(relativePath.substring(relativePath.lastIndexOf('/') + 1));
    }

    static List<TermuxHelperRewrites.Rule> forkRules() throws IOException {
        return TermuxHelperRewrites.parse(TermuxHelperRewrites.render(
            TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE), forkValues()));
    }

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void everyStockHelperIsRewrittenAndNothingStockRemains() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        for (String name : HELPERS) {
            String stock = stockHelper(name);
            String adapted = TermuxHelperRewrites.apply(rules, "bin/" + name, stock);
            Assert.assertNotEquals(name + " should change", stock, adapted);
            for (String line : adapted.split("\n")) {
                Assert.assertFalse(name + ": " + line, line.contains("\"com.termux/")
                    || line.contains("\"com.termux.service_")
                    || line.contains("\"com.termux.app.reload_style\"")
                    || line.contains("echo -n \"com.termux\"")
                    || line.contains("\"com.termux\" >"));
            }
        }
    }

    @Test
    public void javaClassNamesStayUnderTheStockNamespace() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        String reset = TermuxHelperRewrites.apply(rules, "bin/termux-reset", stockHelper("termux-reset"));
        Assert.assertTrue(reset.contains("\"com.termux.termuxam.Am\" stopservice \"dev.pschmitt.termux/com.termux.app.TermuxService\""));
        String open = TermuxHelperRewrites.apply(rules, "bin/termux-open", stockHelper("termux-open"));
        Assert.assertTrue(open.contains("-n \"dev.pschmitt.termux/com.termux.app.TermuxOpenReceiver\""));
        String lock = TermuxHelperRewrites.apply(rules, "bin/termux-wake-lock", stockHelper("termux-wake-lock"));
        Assert.assertTrue(lock.contains("-a \"dev.pschmitt.termux.service_wake_lock\""));
        Assert.assertTrue(lock.contains("\"dev.pschmitt.termux/com.termux.app.TermuxService\""));
        String storage = TermuxHelperRewrites.apply(rules, "bin/termux-setup-storage", stockHelper("termux-setup-storage"));
        Assert.assertTrue(storage.contains("--es \"dev.pschmitt.termux.app.reload_style\" \"storage\""));
        Assert.assertTrue(storage.contains(" \"dev.pschmitt.termux\" > /dev/null"));
    }

    @Test
    public void rewritingIsIdempotent() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        for (String name : HELPERS) {
            String once = TermuxHelperRewrites.apply(rules, "bin/" + name, stockHelper(name));
            Assert.assertEquals(name, once, TermuxHelperRewrites.apply(rules, "bin/" + name, once));
        }
    }

    @Test
    public void stockIdentityLeavesEveryHelperByteIdentical() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = TermuxHelperRewrites.parse(TermuxHelperRewrites.render(
            TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE), stockValues()));
        Assert.assertTrue(rules.isEmpty());
        for (String name : HELPERS) {
            String stock = stockHelper(name);
            Assert.assertEquals(stock, TermuxHelperRewrites.apply(rules, "bin/" + name, stock));
        }
        for (String file : STOCK_FILES) {
            String stock = stockFile(file);
            Assert.assertEquals(file, stock, TermuxHelperRewrites.apply(rules, file, stock));
        }
    }

    @Test
    public void unknownHelpersAndContentAreUntouched() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        String other = "#!/bin/sh\nam start -n \"com.termux/com.termux.app.TermuxOpenReceiver\"\n";
        Assert.assertEquals(other, TermuxHelperRewrites.apply(rules, "bin/termux-something-else", other));
        Assert.assertEquals(other, TermuxHelperRewrites.apply(rules, null, other));
        String unrelated = "#!/bin/sh\necho hello\n";
        for (String name : HELPERS) {
            Assert.assertEquals(unrelated, TermuxHelperRewrites.apply(rules, "bin/" + name, unrelated));
        }
        // A rule only applies to its own helper (the identity rows apply to every file, the rules do not).
        String open = stockHelper("termux-open");
        String asReset = TermuxHelperRewrites.apply(rules, "bin/termux-reset", open);
        Assert.assertTrue(asReset.contains("-n \"com.termux/com.termux.app.TermuxOpenReceiver\""));
    }

    @Test
    public void everyRowMatchesTheRealStockScripts() throws IOException {
        // Guards the table against drifting from the upstream scripts: a row that matches nothing is dead.
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        int[] counts = new int[TermuxHelperRewrites.Kind.values().length];
        for (TermuxHelperRewrites.Rule rule : rules) {
            counts[rule.kind.ordinal()]++;
            switch (rule.kind) {
                case IDENTITY:
                    Assert.assertTrue(stockFile("bin/login").contains(rule.stock));
                    break;
                case RULE:
                    Assert.assertTrue(rule.file + ": " + rule.stock, stockFile(rule.file).contains(rule.stock));
                    break;
                case APPEND:
                    Assert.assertFalse(rule.file, stockFile(rule.file).contains(rule.stock));
                    break;
                case REQUIRE:
                    Assert.assertTrue(rule.file + ": " + rule.stock,
                        TermuxHelperRewrites.apply(rules, rule.file, stockFile(rule.file)).contains(rule.stock));
                    break;
                case FORBID:
                    Assert.assertTrue(rule.file + ": " + rule.stock, stockFile(rule.file).contains(rule.stock));
                    break;
            }
        }
        Assert.assertEquals(1, counts[TermuxHelperRewrites.Kind.IDENTITY.ordinal()]);
        Assert.assertEquals(11, counts[TermuxHelperRewrites.Kind.RULE.ordinal()]);
        Assert.assertEquals(1, counts[TermuxHelperRewrites.Kind.APPEND.ordinal()]);
        Assert.assertEquals(2, counts[TermuxHelperRewrites.Kind.REQUIRE.ordinal()]);
        Assert.assertEquals(9, counts[TermuxHelperRewrites.Kind.FORBID.ordinal()]);
    }

    @Test
    public void everyAdaptedStockFileSatisfiesTheVerification() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        for (String name : HELPERS) {
            String adapted = TermuxHelperRewrites.apply(rules, "bin/" + name, stockHelper(name));
            Assert.assertEquals(name, Collections.emptyList(), TermuxHelperRewrites.verify(rules, "bin/" + name, adapted));
        }
        for (String file : new String[] {"bin/login", "etc/termux-login.sh"}) {
            String adapted = TermuxHelperRewrites.apply(rules, file, stockFile(file));
            Assert.assertEquals(file, Collections.emptyList(), TermuxHelperRewrites.verify(rules, file, adapted));
            // The unadapted stock file must fail: that is what the check is for.
            Assert.assertFalse(file, TermuxHelperRewrites.verify(rules, file, stockFile(file)).isEmpty());
        }
    }

    @Test
    public void loginKeepsTheRedirectPreloadAndTheForkPaths() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        String login = TermuxHelperRewrites.apply(rules, "bin/login", stockFile("bin/login"));
        Assert.assertTrue(login.startsWith("#!/data/data/dev.pschmitt.termux/files/usr/bin/sh\n"));
        Assert.assertFalse(login.contains("/data/data/com.termux"));
        Assert.assertTrue(login.contains("export LD_PRELOAD=\"${TERMUX_PKG_REDIRECT_SO:+$TERMUX_PKG_REDIRECT_SO:}"
            + "/data/data/dev.pschmitt.termux/files/usr/lib/libtermux-exec-ld-preload.so\""));
        Assert.assertTrue(login.contains("export LD_PRELOAD=\"${TERMUX_PKG_REDIRECT_SO:+$TERMUX_PKG_REDIRECT_SO:}"
            + "/data/data/dev.pschmitt.termux/files/usr/lib/libtermux-exec.so\""));
        // The library path itself is never written down.
        Assert.assertFalse(login.contains("libtermux-pkg-redirect"));
        Assert.assertEquals(login, TermuxHelperRewrites.apply(rules, "bin/login", login));
    }

    @Test
    public void conffileGetsTheRedirectPreloadExactlyOnce() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        String stock = stockFile("etc/termux-login.sh");
        String adapted = TermuxHelperRewrites.apply(rules, "etc/termux-login.sh", stock);
        Assert.assertTrue(adapted.startsWith("##\n## This script is sourced by "
            + "/data/data/dev.pschmitt.termux/files/usr/bin/login before executing shell.\n##\n"));
        Assert.assertTrue(adapted.endsWith("\nif [ -n \"${TERMUX_PKG_REDIRECT_SO:-}\" ] && [ -f \"$TERMUX_PKG_REDIRECT_SO\" ]; then\n"
            + "    case \":${LD_PRELOAD:-}:\" in\n"
            + "        *\":$TERMUX_PKG_REDIRECT_SO:\"*) ;;\n"
            + "        *) export LD_PRELOAD=\"$TERMUX_PKG_REDIRECT_SO${LD_PRELOAD:+:$LD_PRELOAD}\" ;;\n"
            + "    esac\nfi\n"));
        Assert.assertEquals(adapted, TermuxHelperRewrites.apply(rules, "etc/termux-login.sh", adapted));
    }

    @Test
    public void identityRewritesAnyFileOfTheBootstrap() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        String pkg = TermuxHelperRewrites.apply(rules, "bin/pkg", stockFile("bin/pkg"));
        Assert.assertTrue(pkg.startsWith("#!/data/data/dev.pschmitt.termux/files/usr/bin/bash\n"));
        Assert.assertFalse(pkg.contains("install-prefix:/data/data/com.termux"));
        Assert.assertEquals("/data/data/dev.pschmitt.termux/files/usr/etc/motd",
            TermuxHelperRewrites.apply(rules, "etc/x", "/data/data/com.termux/files/usr/etc/motd"));
    }

    @Test
    public void verificationReportsAnUpstreamChange() throws IOException {
        List<TermuxHelperRewrites.Rule> rules = forkRules();
        // A new stock reference in a helper, and a login whose preload line changed shape.
        String open = TermuxHelperRewrites.apply(rules, "bin/termux-open",
            stockHelper("termux-open") + "am broadcast -n \"com.termux/com.termux.app.New\"\n");
        Assert.assertEquals(1, TermuxHelperRewrites.verify(rules, "bin/termux-open", open).size());
        String login = TermuxHelperRewrites.apply(rules, "bin/login",
            stockFile("bin/login").replace("export LD_PRELOAD=\"/", "export LD_PRELOAD=$X/"));
        Assert.assertEquals(1, TermuxHelperRewrites.verify(rules, "bin/login", login).size());
    }

    @Test
    public void incompleteIdentityFailsSafe() {
        String template = "rule\tbin/termux-open\t-n \"com.termux/x\"\t-n \"@PACKAGE@/x\"\n";
        Assert.assertTrue(TermuxHelperRewrites.parse(TermuxHelperRewrites.render(template, new HashMap<>())).isEmpty());
        Map<String, String> empty = new HashMap<>();
        empty.put("PACKAGE", "");
        Assert.assertTrue(TermuxHelperRewrites.parse(TermuxHelperRewrites.render(template, empty)).isEmpty());
    }

    @Test
    public void malformedAndNonWhitelistedRulesAreDropped() {
        String table = "# comment\n"
            + "rule\tbin/termux-open\tstock\n"
            + "rule\tbin/../etc/passwd\tstock\tfork\n"
            + "rule\tbin/sh\tstock\tfork\n"
            + "append\tetc/profile\tmarker\ttext\n"
            + "rule\tbin/termux-open\tprefix\tprefix-x\n"
            + "identity\tsame\tsame\n"
            + "identity\tonly\n"
            + "require\tbin/login\ttext\n"
            + "forbid\tbin/login\ttext\t@FORK_ONLY@\n"
            + "rule\tbin/termux-open\t\tfork\n"
            + "rule\tbin/termux-open\tsame\tsame\n"
            + "junk\tbin/termux-open\tstock\tfork\n"
            + "rule\tbin/termux-open\tstock\tfork\n";
        List<TermuxHelperRewrites.Rule> rules = TermuxHelperRewrites.parse(table);
        // Only the last rule survives; "prefix" -> "prefix-x" would match its own result and is dropped.
        Assert.assertEquals(1, rules.size());
        Assert.assertEquals("fork", rules.get(0).fork);
    }

    @Test
    public void deployHookWritesTheHookTableAndAptConfig() throws IOException {
        File staging = temp.newFolder("usr.staging");
        TermuxHelperRewrites.deployHook(staging, "/data/data/dev.pschmitt.termux/files/usr", forkValues());

        String script = new String(Files.readAllBytes(new File(staging, "libexec/termuxpp/adapt-helpers").toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(script.startsWith("#!/data/data/dev.pschmitt.termux/files/usr/bin/bash\n"));
        Assert.assertTrue(new File(staging, "libexec/termuxpp/adapt-helpers").canExecute());

        String table = new String(Files.readAllBytes(new File(staging, "libexec/termuxpp/helper-rewrites.tsv").toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(table.startsWith("prefix\t/data/data/dev.pschmitt.termux/files/usr\n"));
        Assert.assertEquals(forkRules().size(), TermuxHelperRewrites.parse(table).size());
        Assert.assertFalse(table.contains("@PACKAGE@"));
        Assert.assertFalse(table.contains("@DATA_DIR@"));

        String apt = new String(Files.readAllBytes(new File(staging, "etc/apt/apt.conf.d/99termuxpp-helpers").toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(apt.contains("DPkg::Post-Invoke { \"/data/data/dev.pschmitt.termux/files/usr/bin/bash "
            + "/data/data/dev.pschmitt.termux/files/usr/libexec/termuxpp/adapt-helpers\"; };"));
        // A failing hook must make apt report an error.
        Assert.assertFalse(apt.contains("|| true"));
    }

    @Test
    public void unsafePrefixesAreRefused() throws IOException {
        File staging = temp.newFolder("usr.staging2");
        for (String prefix : Arrays.asList("relative/path", "/data/../etc", "/data/a b", "/data/a\"b", "/data/a\nb", "")) {
            try {
                TermuxHelperRewrites.deployHook(staging, prefix, forkValues());
                Assert.fail("accepted " + prefix);
            } catch (IOException expected) {
                // refused
            }
        }
        Assert.assertEquals(0, staging.list().length);
    }

    @Test
    public void hookTableRendersTheSameRulesAsTheInstaller() throws IOException {
        String rendered = TermuxHelperRewrites.render(
            TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE), forkValues());
        Assert.assertEquals(forkRules().size(),
            TermuxHelperRewrites.parse(TermuxHelperRewrites.hookTable("/p", rendered)).size());
    }

    @Test
    public void strictParseAcceptsTheRealForkTableWithTwentyFourRows() throws IOException {
        String rendered = TermuxHelperRewrites.render(
            TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE), forkValues());
        Assert.assertEquals(24, TermuxHelperRewrites.parseStrict(rendered).size());
        Assert.assertEquals(TermuxHelperRewrites.parse(rendered).size(), TermuxHelperRewrites.parseStrict(rendered).size());
    }

    @Test
    public void strictParseRejectsEveryRowThatLenientParseWouldDrop() throws IOException {
        String rendered = TermuxHelperRewrites.render(
            TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE), forkValues());
        String[] bad = {"bogus\tx\ty\n", "rule\tbin/login\tonly\n", "rule\tbin/evil\tx\ty\n",
            "rule\tbin/login\tx\tx\n", "rule\tbin/login\tx\txx\n", "rule\tbin/login\t@UNRESOLVED@\ty\n",
            "identity\t\tx\n", "append\tbin/login\t\tx\n"};
        for (String row : bad) {
            Assert.assertEquals(row, TermuxHelperRewrites.parse(rendered).size(),
                TermuxHelperRewrites.parse(rendered + row).size());
            try {
                TermuxHelperRewrites.parseStrict(rendered + row);
                Assert.fail("strict parse should reject: " + row);
            } catch (IOException expected) {
                Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("Unusable row"));
            }
        }
    }

    @Test
    public void strictParseOfTheStockRenderingRejectsTheForkRowsThatVanish() throws IOException {
        // The stock rendering leaves @FORK_ONLY@ and @REDIRECT_SO_VAR@ unresolved: parse() drops those rows by
        // design (stock stays untouched), parseStrict() is for the fork identity only and says so.
        String rendered = TermuxHelperRewrites.render(
            TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE), stockValues());
        try {
            TermuxHelperRewrites.parseStrict(rendered);
            Assert.fail("the stock rendering is not a complete fork table");
        } catch (IOException expected) {
            // fine
        }
    }

    @Test
    public void deployHookRefusesAnUnresolvablePlaceholderBeforeWritingAnything() throws IOException {
        File staging = temp.newFolder("staging-strict");
        Map<String, String> values = forkValues();
        values.remove("REDIRECT_SO_VAR");
        try {
            TermuxHelperRewrites.deployHook(staging, "/data/data/dev.pschmitt.termux/files/usr", values);
            Assert.fail("deployHook must refuse");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("Unusable row"));
        }
        Assert.assertEquals(0, staging.list().length);
    }
}
