package com.termux.app.terminal;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ReportIssueRequestTest {

    private long mNow;
    private final ReportIssueRequest.Clock mClock = () -> mNow;

    @Before
    @After
    public void clear() {
        ReportIssueRequest.clear();
        mNow = 1_000L;
    }

    @Test
    public void nothingIsRequestedByDefault() {
        Assert.assertFalse(ReportIssueRequest.consume(mClock));
    }

    @Test
    public void aRequestIsConsumedExactlyOnce() {
        ReportIssueRequest.request(mClock);
        Assert.assertTrue(ReportIssueRequest.consume(mClock));
        Assert.assertFalse(ReportIssueRequest.consume(mClock));
    }

    @Test
    public void aRequestExpires() {
        ReportIssueRequest.request(mClock);
        mNow += ReportIssueRequest.TIMEOUT_MS + 1;
        Assert.assertFalse(ReportIssueRequest.consume(mClock));
        // An expired request is dropped, not kept for later.
        mNow = 1_000L;
        Assert.assertFalse(ReportIssueRequest.consume(mClock));
    }

    @Test
    public void aRequestJustInsideTheTimeoutStillCounts() {
        ReportIssueRequest.request(mClock);
        mNow += ReportIssueRequest.TIMEOUT_MS;
        Assert.assertTrue(ReportIssueRequest.consume(mClock));
    }

}
