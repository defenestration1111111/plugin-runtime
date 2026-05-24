package com.defenestration1111111.plugin.spring;

import com.defenestration1111111.plugin.core.discovery.DiscoveryReport;
import com.defenestration1111111.plugin.core.discovery.PluginDiscovery;
import com.defenestration1111111.plugin.core.lifecycle.PluginManager;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PluginSystemLifecycle implements SmartLifecycle {

    /** Start after most beans, stop before them. Web server uses MAX_VALUE-1 so we leave it last. */
    static final int PHASE = Integer.MAX_VALUE - 1024;

    private static final Logger LOG = Logger.getLogger(PluginSystemLifecycle.class.getName());

    private final PluginManager manager;
    private final PluginSystemProperties props;
    private volatile boolean running;

    public PluginSystemLifecycle(PluginManager manager, PluginSystemProperties props) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.props = Objects.requireNonNull(props, "props");
    }

    @Override
    public synchronized void start() {
        if (running) return;
        Path directory = props.getDirectory();
        if (directory != null && Files.isDirectory(directory)) {
            DiscoveryReport report = new PluginDiscovery().scan(directory);
            manager.install(report);
            for (var failure : report.failures()) {
                LOG.log(Level.WARNING, "Plugin discovery failure: " + failure.jar(), failure.error());
            }
            if (props.isAutoStart()) {
                manager.startAll();
            }
            if (props.isWatch()) {
                try {
                    manager.attachWatcher(directory);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Failed to attach plugin watcher to " + directory, e);
                }
            }
        }
        running = true;
    }

    @Override
    public synchronized void stop() {
        if (!running) return;
        try {
            manager.detachWatcher();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to detach plugin watcher", e);
        }
        manager.stopAll();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
