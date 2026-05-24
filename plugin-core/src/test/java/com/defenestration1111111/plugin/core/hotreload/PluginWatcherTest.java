package com.defenestration1111111.plugin.core.hotreload;

import com.defenestration1111111.plugin.core.hotreaload.PluginWatcher;
import com.defenestration1111111.plugin.core.hotreaload.WatcherHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PluginWatcherTest {

    private static final long DETECT_TIMEOUT_MS = 15_000;
    private static final long DEBOUNCE_MS = 200;

    static final class RecordingHandler implements WatcherHandler {
        final List<Path> settled = new CopyOnWriteArrayList<>();
        final List<Path> deleted = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void onSettled(Path jar) {
            settled.add(jar);
            calls.incrementAndGet();
        }

        @Override
        public void onDeleted(Path jar) {
            deleted.add(jar);
            calls.incrementAndGet();
        }
    }

    @Test
    void detectsCreate(@TempDir Path dir) throws Exception {
        RecordingHandler h = new RecordingHandler();
        try (PluginWatcher w = new PluginWatcher(dir, h, DEBOUNCE_MS)) {
            Path jar = dir.resolve("a.jar");
            Files.write(jar, new byte[]{1});

            waitForCalls(h, 1);

            assertThat(h.settled).contains(jar);
            assertThat(h.deleted).isEmpty();
        }
    }

    @Test
    void detectsDelete(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("b.jar");
        Files.write(jar, new byte[]{1});

        RecordingHandler h = new RecordingHandler();
        try (PluginWatcher w = new PluginWatcher(dir, h, DEBOUNCE_MS)) {
            Files.delete(jar);

            waitForCalls(h, 1);

            assertThat(h.deleted).contains(jar);
        }
    }

    @Test
    void debouncesRapidWritesIntoSingleSettled(@TempDir Path dir) throws Exception {
        RecordingHandler h = new RecordingHandler();
        try (PluginWatcher w = new PluginWatcher(dir, h, DEBOUNCE_MS)) {
            Path jar = dir.resolve("c.jar");
            Files.write(jar, new byte[]{1});
            // Several rapid modifications inside one debounce window.
            for (int i = 0; i < 5; i++) {
                Files.write(jar, new byte[]{(byte) i, (byte) i, (byte) i});
                Thread.sleep(20);
            }

            waitForCalls(h, 1);
            // Allow extra time to confirm no second callback fires.
            Thread.sleep(DEBOUNCE_MS + 200);

            assertThat(h.settled).hasSize(1).contains(jar);
        }
    }

    @Test
    void ignoresNonJarFiles(@TempDir Path dir) throws Exception {
        RecordingHandler h = new RecordingHandler();
        try (PluginWatcher w = new PluginWatcher(dir, h, DEBOUNCE_MS)) {
            Files.write(dir.resolve("not-a-plugin.txt"), new byte[]{1});
            // Give the watch service plenty of time; we expect zero calls.
            Thread.sleep(DETECT_TIMEOUT_MS / 5);
            assertThat(h.calls.get()).isZero();
        }
    }

    private static void waitForCalls(RecordingHandler h, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DETECT_TIMEOUT_MS);
        while (h.calls.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(h.calls.get())
                .as("watcher should have fired at least %d callback(s) within %d ms",
                        expected, DETECT_TIMEOUT_MS)
                .isGreaterThanOrEqualTo(expected);
    }
}
