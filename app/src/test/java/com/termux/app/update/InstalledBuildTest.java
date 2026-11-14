package com.termux.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InstalledBuildTest {

    private static final String FULL = "0123456789abcdef0123456789abcdef01234567";

    @Test
    public void bareHashesAreComparable() {
        assertEquals(FULL, InstalledBuild.parse(FULL).commit);
        assertEquals("0123456", InstalledBuild.parse(" 0123456 ").commit);
        assertEquals(FULL, InstalledBuild.parse(FULL.toUpperCase()).commit);
        assertTrue(InstalledBuild.parse(FULL).isComparable());
    }

    @Test
    public void describeOutputIsComparable() {
        assertEquals("abcdef0", InstalledBuild.parse("v0.118.0-12-gabcdef0").commit);
        assertEquals("abcdef0", InstalledBuild.parse("0.118.0-1-gabcdef0").commit);
    }

    @Test
    public void everythingElseIsNot() {
        assertFalse(InstalledBuild.parse(null).isComparable());
        assertFalse(InstalledBuild.parse("").isComparable());
        assertFalse(InstalledBuild.parse("unknown").isComparable());
        assertFalse(InstalledBuild.parse(FULL + "-dirty").isComparable());
        assertFalse(InstalledBuild.parse("abc123").isComparable());
        assertFalse(InstalledBuild.parse(FULL + "0").isComparable());
        assertFalse(InstalledBuild.parse("v0.118.0-12-gabcdef0-dirty").isComparable());
    }

    @Test
    public void companionMetaDataNeedsTheGitPrefix() {
        assertEquals("abcdef0", InstalledBuild.parseMetaData("git-abcdef0").commit);
        assertEquals(FULL, InstalledBuild.parseMetaData(" git-" + FULL.toUpperCase() + " ").commit);
        assertTrue(InstalledBuild.parseMetaData("git-" + FULL).isComparable());
    }

    @Test
    public void companionMetaDataThatIsNotACleanCommitIsNot() {
        assertFalse(InstalledBuild.parseMetaData(null).isComparable());
        assertFalse(InstalledBuild.parseMetaData("").isComparable());
        assertFalse(InstalledBuild.parseMetaData(FULL).isComparable());
        assertFalse(InstalledBuild.parseMetaData("git-").isComparable());
        assertFalse(InstalledBuild.parseMetaData("git-unknown").isComparable());
        assertFalse(InstalledBuild.parseMetaData("git-" + FULL + "-dirty").isComparable());
        assertFalse(InstalledBuild.parseMetaData("git-abc12").isComparable());
        assertFalse(InstalledBuild.parseMetaData("git-" + FULL + "0").isComparable());
        assertFalse(InstalledBuild.parseMetaData("git-v0.1.0-1-gabcdef0").isComparable());
    }
}
