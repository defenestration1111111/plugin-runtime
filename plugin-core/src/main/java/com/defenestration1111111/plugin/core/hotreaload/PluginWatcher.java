package com.defenestration1111111.plugin.core.hotreaload;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PluginWatcher implements AutoCloseable {

    private static final long DEBOUNCE_MS_DEFAULT = 500;
    private static final Logger LOG = Logger.getLogger(PluginWatcher.class.getName());

    private final Path directory;
    private final WatcherHandler handler;
    private final long debounceMillis;
    private final WatchService watchService;
    private final Thread thread;
    private final ScheduledExecutorService debouncer;
    private final Map<Path, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public PluginWatcher(Path directory, WatcherHandler handler) throws IOException {
        this(directory, handler, DEBOUNCE_MS_DEFAULT);
    }

    public PluginWatcher(Path directory, WatcherHandler handler, long debounceMillis) throws IOException {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.handler = Objects.requireNonNull(handler, "handler");
        if (debounceMillis < 0) {
            throw new IllegalArgumentException("debounceMillis must be >= 0");
        }
        this.debounceMillis = debounceMillis;
        this.watchService = directory.getFileSystem().newWatchService();
        directory.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        this.debouncer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "plugin-watcher-debounce");
            t.setDaemon(true);
            return t;
        });
        this.thread = new Thread(this::run, "plugin-watcher-" + directory.getFileName());
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void run() {
        while (!closed) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) continue;
                Object ctx = event.context();
                if (!(ctx instanceof Path relative)) continue;
                Path absolute = directory.resolve(relative);
                if (!absolute.getFileName().toString().endsWith(".jar")) continue;
                schedule(absolute, kind);
            }
            if (!key.reset()) break;
        }
    }

    private void schedule(Path path, WatchEvent.Kind<?> kind) {
        ScheduledFuture<?> previous = pending.remove(path);
        if (previous != null) previous.cancel(false);

        boolean isDelete = kind == StandardWatchEventKinds.ENTRY_DELETE;
        Runnable task = () -> {
            pending.remove(path);
            try {
                if (isDelete) {
                    handler.onDeleted(path);
                } else {
                    handler.onSettled(path);
                }
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "Plugin watcher handler failed for " + path, t);
            }
        };
        ScheduledFuture<?> future = debouncer.schedule(task, debounceMillis, TimeUnit.MILLISECONDS);
        pending.put(path, future);
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        debouncer.shutdownNow();
        try {
            watchService.close();
        } finally {
            thread.interrupt();
        }
    }
}
