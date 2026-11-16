package com.termux.app.update;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
public class UpdateControllerTest {

    private static final class BlockingNetwork implements UpdateNetwork {
        final CountDownLatch firstWave = new CountDownLatch(3);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maximumActive = new AtomicInteger();

        @Override
        public String fetchApiJson(String url) throws IOException {
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            firstWave.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timed out");
                throw new HttpStatusException(404);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } finally {
                active.decrementAndGet();
            }
        }

        @Override
        public String fetchAssetText(String url, long maxBytes) {
            throw new AssertionError("404 release checks must not fetch assets");
        }

        @Override
        public void download(String url, File dest, long maxBytes, ProgressListener listener) {
            throw new AssertionError("release checks must not download");
        }

        @Override
        public long freeBytes(File dir) {
            return Long.MAX_VALUE;
        }
    }

    @Test
    public void checkAllRunsAtMostThreeNetworkChecksAtOnce() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        BlockingNetwork network = new BlockingNetwork();
        UpdateController controller = new UpdateController(context, network);
        try {
            controller.checkAll(UpdateChannel.LATEST);
            assertTrue("checks did not start concurrently", network.firstWave.await(5, TimeUnit.SECONDS));
            assertTrue(network.maximumActive.get() <= 3);
            network.release.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (controller.isBusy() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue("checks did not finish", !controller.isBusy());
            assertTrue(network.maximumActive.get() <= 3);
        } finally {
            network.release.countDown();
        }
    }

    @Test
    public void cancellingParallelChecksInterruptsWorkersAndReturnsToIdle() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        BlockingNetwork network = new BlockingNetwork();
        UpdateController controller = new UpdateController(context, network);
        controller.checkAll(UpdateChannel.LATEST);
        assertTrue("checks did not start concurrently", network.firstWave.await(5, TimeUnit.SECONDS));
        assertTrue(controller.isCancellable());

        controller.cancel();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (controller.isBusy() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("cancelled checks did not finish", !controller.isBusy());
        assertTrue("cancel did not interrupt network workers", network.active.get() == 0);
        for (UpdateTarget target : UpdateTarget.values()) {
            assertTrue(target.id, controller.getState(target).phase != UpdateState.Phase.CHECKING);
        }
    }
}
