package com.defenestration1111111.plugin.core.lifecycle;

import com.defenestration1111111.plugin.api.*;
import com.defenestration1111111.plugin.core.classloader.PluginClassLoader;
import com.defenestration1111111.plugin.core.discovery.DiscoveredPlugin;
import com.defenestration1111111.plugin.core.discovery.DiscoveryReport;
import com.defenestration1111111.plugin.core.discovery.PluginDiscovery;
import com.defenestration1111111.plugin.core.extension.ExtensionRegistry;
import com.defenestration1111111.plugin.core.hotreaload.PluginWatcher;
import com.defenestration1111111.plugin.core.hotreaload.WatcherHandler;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PluginManager implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PluginManager.class.getName());

    private final ClassLoader hostClassLoader;
    private final Set<String> exportedPackages;
    private final Map<String, ManagedPlugin> plugins = new ConcurrentHashMap<>();
    private final Map<Path, String> jarIndex = new ConcurrentHashMap<>();
    private final Map<String, DefaultPluginContext> contexts = new ConcurrentHashMap<>();
    private final ExtensionRegistry extensionRegistry = new ExtensionRegistry();
    private volatile boolean autoStartOnDiscovery = true;
    private volatile PluginWatcher watcher;

    public PluginManager(ClassLoader hostClassLoader) {
        this(hostClassLoader, PluginClassLoader.BASELINE_EXPORTED_PACKAGES);
    }

    public PluginManager(ClassLoader hostClassLoader, Set<String> exportedPackages) {
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.exportedPackages = Set.copyOf(Objects.requireNonNull(exportedPackages, "exportedPackages"));
    }

    public void install(DiscoveredPlugin discovered) {
        Objects.requireNonNull(discovered, "discovered");
        ManagedPlugin mp = new ManagedPlugin(discovered.descriptor(), discovered.jar());
        ManagedPlugin existing = plugins.putIfAbsent(discovered.descriptor().id(), mp);
        if (existing != null) {
            throw new PluginLifecycleException(
                    "Plugin already installed: " + discovered.descriptor().id());
        }
        jarIndex.put(discovered.jar(), discovered.descriptor().id());
    }

    public void install(DiscoveryReport report) {
        Objects.requireNonNull(report, "report");
        for (DiscoveredPlugin p : report.plugins()) {
            install(p);
        }
    }

    public void resolve(String id) {
        ManagedPlugin mp = require(id);
        mp.lock.lock();
        try {
            requirePhase(mp, Phase.DISCOVERED, "resolve");
            mp.phase = Phase.RESOLVED;
        } finally {
            mp.lock.unlock();
        }
    }

    public void load(String id) {
        ManagedPlugin mp = require(id);
        mp.lock.lock();
        try {
            requirePhase(mp, Phase.RESOLVED, "load");
            PluginClassLoader cl = null;
            try {
                cl = new PluginClassLoader(jarUrls(mp.jar), hostClassLoader, exportedPackages);
                Plugin instance = instantiate(mp.descriptor, cl);
                mp.classLoader = cl;
                mp.instance = instance;
                mp.phase = Phase.LOADED;
            } catch (Throwable t) {
                fail(mp, t, cl);
                throw asPluginException(t, "Failed to load plugin " + id);
            }
        } finally {
            mp.lock.unlock();
        }
    }

    public void start(String id) {
        ManagedPlugin mp = require(id);
        mp.lock.lock();
        try {
            requirePhase(mp, Phase.LOADED, "start");
            DefaultPluginContext ctx = new DefaultPluginContext(mp.descriptor, extensionRegistry);
            try {
                contexts.put(id, ctx);
                mp.instance.start(ctx);
                mp.phase = Phase.STARTED;
            } catch (Throwable t) {
                contexts.remove(id);
                extensionRegistry.unregisterAll(id);
                fail(mp, t, mp.classLoader);
                throw asPluginException(t, "Failed to start plugin " + id);
            }
        } finally {
            mp.lock.unlock();
        }
    }

    public void stop(String id) {
        ManagedPlugin mp = require(id);
        mp.lock.lock();
        try {
            requirePhase(mp, Phase.STARTED, "stop");
            try {
                mp.instance.stop();
                mp.phase = Phase.STOPPED;
            } catch (Throwable t) {
                fail(mp, t, mp.classLoader);
                throw asPluginException(t, "Failed to stop plugin " + id);
            } finally {
                contexts.remove(id);
                extensionRegistry.unregisterAll(id);
            }
        } finally {
            mp.lock.unlock();
        }
    }

    public void unload(String id) {
        ManagedPlugin mp = require(id);
        mp.lock.lock();
        try {
            if (mp.phase != Phase.LOADED && mp.phase != Phase.STOPPED) {
                throw new PluginLifecycleException(
                        "Cannot unload plugin " + id + " from phase " + mp.phase);
            }
            closeQuietly(mp.classLoader);
            mp.instance = null;
            mp.classLoader = null;
            mp.phase = Phase.UNLOADED;
        } finally {
            mp.lock.unlock();
        }
    }

    public void startAll() {
        for (String id : plugins.keySet()) {
            ManagedPlugin mp = plugins.get(id);
            if (mp == null) continue;
            try {
                if (mp.phase == Phase.DISCOVERED) resolve(id);
                if (mp.phase == Phase.RESOLVED) load(id);
                if (mp.phase == Phase.LOADED) start(id);
            } catch (RuntimeException ignore) {
                // per-plugin failure already recorded; continue with others
            }
        }
    }

    public void stopAll() {
        for (String id : plugins.keySet()) {
            ManagedPlugin mp = plugins.get(id);
            if (mp == null) continue;
            if (mp.phase != Phase.STARTED) continue;
            try {
                stop(id);
            } catch (RuntimeException ignore) {
                // best-effort
            }
        }
    }

    public Phase phase(String id) {
        return require(id).phase;
    }

    public Optional<Throwable> failure(String id) {
        return Optional.ofNullable(require(id).failureCause);
    }

    public Set<String> ids() {
        return Set.copyOf(plugins.keySet());
    }

    public <T extends ExtensionPoint> List<T> getExtensions(Class<T> extensionPoint) {
        return extensionRegistry.getExtensions(extensionPoint);
    }

    public void reload(String id, Path newJar) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(newJar, "newJar");

        // Read new descriptor first — fail fast before tearing down the live plugin.
        DiscoveredPlugin discovered;
        try {
            discovered = PluginDiscovery.read(newJar);
        } catch (RuntimeException e) {
            throw new PluginLifecycleException("Failed to read new jar for reload of " + id, e);
        }
        if (!discovered.descriptor().id().equals(id)) {
            throw new PluginLifecycleException(
                    "Reload jar declares id '" + discovered.descriptor().id()
                            + "', expected '" + id + "'");
        }
        performReload(id, discovered);
    }

    private void performReload(String id, DiscoveredPlugin discovered) {
        ManagedPlugin oldMp = require(id);
        ManagedPlugin freshMp = new ManagedPlugin(discovered.descriptor(), discovered.jar());
        // Lock freshMp before it enters the map so concurrent callers block until fully started.
        freshMp.lock.lock();
        try {
            oldMp.lock.lock();
            try {
                if (plugins.get(id) != oldMp) {
                    throw new PluginLifecycleException("Plugin " + id + " was concurrently modified");
                }

                if (oldMp.phase == Phase.STARTED) {
                    stop(id);
                }
                if (oldMp.phase == Phase.LOADED || oldMp.phase == Phase.STOPPED) {
                    unload(id);
                }
                jarIndex.remove(oldMp.jar);
                jarIndex.put(freshMp.jar, id);
                plugins.put(id, freshMp);
            } finally {
                oldMp.lock.unlock();
            }
            // resolve/load/start re-enter freshMp.lock (ReentrantLock).
            resolve(id);
            load(id);
            start(id);
        } finally {
            freshMp.lock.unlock();
        }
    }

    public void uninstall(String id) {
        ManagedPlugin mp = require(id);
        mp.lock.lock();
        try {
            if (mp.phase == Phase.STARTED) {
                try {
                    stop(id);
                } catch (RuntimeException ignore) {
                    // best-effort; still proceed to remove
                }
            }
            if (mp.phase == Phase.LOADED || mp.phase == Phase.STOPPED) {
                try {
                    unload(id);
                } catch (RuntimeException ignore) {
                    // best-effort
                }
            }
            jarIndex.remove(mp.jar);
            plugins.remove(id, mp);
        } finally {
            mp.lock.unlock();
        }
    }

    public boolean isAutoStartOnDiscovery() {
        return autoStartOnDiscovery;
    }

    public void setAutoStartOnDiscovery(boolean autoStart) {
        this.autoStartOnDiscovery = autoStart;
    }

    public synchronized void attachWatcher(Path directory) throws IOException {
        if (watcher != null) {
            throw new IllegalStateException("Watcher already attached");
        }
        watcher = new PluginWatcher(directory, new  WatcherHandler() {
            @Override public void onSettled(Path jar) {
                handleSettled(jar);
            }

            @Override public void onDeleted(Path jar) {
                handleDeleted(jar);
            }
        });
    }

    public synchronized void detachWatcher() throws IOException {
        if (watcher == null) return;
        watcher.close();
        watcher = null;
    }

    void handleSettled(Path jar) {
        DiscoveredPlugin discovered;
        try {
            discovered = PluginDiscovery.read(jar);
        } catch (RuntimeException e) {
            // Partial write or invalid descriptor — next event will retry.
            LOG.log(Level.FINE, "Skipping settled event for " + jar + ": " + e.getMessage());
            return;
        }
        String id = discovered.descriptor().id();
        if (plugins.containsKey(id)) {
            try {
                performReload(id, discovered);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Reload failed for " + id, e);
            }
        } else {
            try {
                install(discovered);
                if (autoStartOnDiscovery) {
                    resolve(id);
                    load(id);
                    start(id);
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Install via watcher failed for " + jar, e);
            }
        }
    }

    void handleDeleted(Path jar) {
        String found = jarIndex.get(jar);
        if (found == null) return;
        try {
            uninstall(found);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Uninstall via watcher failed for " + found, e);
        }
    }

    @Override
    public void close() {
        try {
            detachWatcher();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to close plugin watcher", e);
        }
        stopAll();
        for (String id : plugins.keySet()) {
            ManagedPlugin mp = plugins.get(id);
            if (mp == null) continue;
            if (mp.phase == Phase.LOADED || mp.phase == Phase.STOPPED) {
                try {
                    unload(id);
                } catch (RuntimeException ignore) {
                    // best-effort
                }
            }
        }
    }

    private ManagedPlugin require(String id) {
        Objects.requireNonNull(id, "id");
        ManagedPlugin mp = plugins.get(id);
        if (mp == null) {
            throw new PluginLifecycleException("Unknown plugin: " + id);
        }
        return mp;
    }

    private static void requirePhase(ManagedPlugin mp, Phase expected, String op) {
        if (mp.phase != expected) {
            throw new PluginLifecycleException(
                    "Cannot " + op + " plugin " + mp.descriptor.id()
                            + " from phase " + mp.phase + " (expected " + expected + ")");
        }
    }

    private static URL[] jarUrls(java.nio.file.Path jar) {
        try {
            return new URL[]{jar.toUri().toURL()};
        } catch (MalformedURLException e) {
            throw new PluginLoadException("Invalid jar URL: " + jar, e);
        }
    }

    private static Plugin instantiate(PluginDescriptor descriptor, PluginClassLoader cl) {
        Class<?> mainClass;
        try {
            mainClass = cl.loadClass(descriptor.mainClass());
        } catch (ClassNotFoundException e) {
            throw new PluginLoadException("Main class not found: " + descriptor.mainClass(), e);
        }
        if (!Plugin.class.isAssignableFrom(mainClass)) {
            throw new PluginLoadException(
                    "Main class does not implement Plugin: " + descriptor.mainClass());
        }
        Constructor<?> ctor;
        try {
            ctor = mainClass.getConstructor();
        } catch (NoSuchMethodException e) {
            throw new PluginLoadException(
                    "Main class has no public no-arg constructor: " + descriptor.mainClass(), e);
        }
        try {
            return (Plugin) ctor.newInstance();
        } catch (InvocationTargetException e) {
            throw new PluginLoadException(
                    "Plugin constructor threw: " + descriptor.mainClass(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new PluginLoadException(
                    "Failed to instantiate plugin: " + descriptor.mainClass(), e);
        }
    }

    private static void fail(ManagedPlugin mp, Throwable cause, PluginClassLoader clToClose) {
        mp.failureCause = cause;
        closeQuietly(clToClose);
        mp.classLoader = null;
        mp.instance = null;
        mp.phase = Phase.FAILED;
    }

    private static void closeQuietly(PluginClassLoader cl) {
        if (cl == null) return;
        try {
            cl.close();
        } catch (IOException ignore) {
            // best-effort
        }
    }

    private static RuntimeException asPluginException(Throwable t, String message) {
        if (t instanceof PluginLoadException ple) return ple;
        if (t instanceof PluginLifecycleException ple) return ple;
        if (t instanceof RuntimeException re) return re;
        return new PluginLifecycleException(message, t);
    }
}
