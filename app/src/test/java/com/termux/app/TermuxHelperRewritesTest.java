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
        return values;
    }

    static Map<String, String> forkValues() {
        Map<String, String> values = new HashMap<>();
        values.put("PACKAGE", "dev.pschmitt.termux");
        values.put("RELOAD_STYLE_ACTION", "dev.pschmitt.termux.app.reload_style");
        values.put("WAKE_LOCK_ACTION", "dev.pschmitt.termux.service_wake_lock");
        values.put("WAKE_UNLOCK_ACTION", "dev.pschmitt.termux.service_wake_unlock");
        return values;
    }

    static String stockHelper(String name) throws IOException {
        return TermuxHelperRewrites.readResource("/termuxpp/stock/" + name);
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
        // A rule only applies to its own helper.
        String open = stockHelper("termux-open");
        Assert.assertEquals(open, TermuxHelperRewrites.apply(rules, "bin/termux-reset", open));
    }

    @Test
    public void everyRuleMatchesTheRealStockScripts() throws IOException {
        // Guards the table against drifting from the upstream scripts: a rule that matches nothing is dead.
        List<TermuxHelperRewrites.Rule> rules = TermuxHelperRewrites.parse(TermuxHelperRewrites.render(
            TermuxHelperRewrites.readResource(TermuxHelperRewrites.TABLE_RESOURCE), forkValues()));
        Assert.assertEquals(10, rules.size());
        for (TermuxHelperRewrites.Rule rule : rules) {
            Assert.assertTrue(rule.file + ": " + rule.stock,
                stockHelper(rule.file.substring("bin/".length())).contains(rule.stock));
        }
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
            + "rule\tbin/login\tstock\tfork\n"
            + "rule\tbin/termux-open\t\tfork\n"
            + "rule\tbin/termux-open\tsame\tsame\n"
            + "junk\tbin/termux-open\tstock\tfork\n"
            + "rule\tbin/termux-open\tstock\tfork\n";
        List<TermuxHelperRewrites.Rule> rules = TermuxHelperRewrites.parse(table);
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
        Assert.assertEquals(10, TermuxHelperRewrites.parse(table).size());
        Assert.assertFalse(table.contains("@PACKAGE@"));

        String apt = new String(Files.readAllBytes(new File(staging, "etc/apt/apt.conf.d/99termuxpp-helpers").toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(apt.contains("DPkg::Post-Invoke { \"/data/data/dev.pschmitt.termux/files/usr/bin/bash "
            + "/data/data/dev.pschmitt.termux/files/usr/libexec/termuxpp/adapt-helpers || true\"; };"));
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
}
