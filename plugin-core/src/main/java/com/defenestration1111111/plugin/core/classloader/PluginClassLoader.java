package com.defenestration1111111.plugin.core.classloader;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class PluginClassLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    public static final Set<String> BASELINE_EXPORTED_PACKAGES = Set.of("com.defenestration1111111.plugin.api");

    private static final List<String> CORE_PLATFORM_PREFIXES = List.of("java.", "jdk.", "sun.");

    private final Set<String> exportedPackages;
    private final ClassLoader hostClassLoader;

    public PluginClassLoader(URL[] urls, ClassLoader hostClassLoader, Set<String> exportedPackages) {
        super(urls, ClassLoader.getPlatformClassLoader());
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.exportedPackages = Set.copyOf(exportedPackages);
    }

    public PluginClassLoader(URL[] urls, ClassLoader hostClassLoader) {
        super(urls);
        this.hostClassLoader = hostClassLoader;
        this.exportedPackages = BASELINE_EXPORTED_PACKAGES;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (isCorePlatformClass(name)) {
                    return super.loadClass(name, resolve);
                } else if (isExported(name)) {
                    c = hostClassLoader.loadClass(name);
                } else {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException notInPlugin) {
                        c = hostClassLoader.loadClass(name);
                    }
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    public Set<String> exportedPackages() {
        return exportedPackages;
    }

    private static boolean isCorePlatformClass(String name) {
        for (String prefix : CORE_PLATFORM_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExported(String className) {
        int lastDot = className.lastIndexOf('.');
        if (lastDot < 0) {
            return false;
        }
        return exportedPackages.contains(className.substring(0, lastDot));
    }
}
