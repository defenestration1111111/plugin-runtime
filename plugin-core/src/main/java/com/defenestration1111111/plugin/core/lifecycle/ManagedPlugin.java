package com.defenestration1111111.plugin.core.lifecycle;

import com.defenestration1111111.plugin.api.Plugin;
import com.defenestration1111111.plugin.api.PluginDescriptor;
import com.defenestration1111111.plugin.core.classloader.PluginClassLoader;

import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

final class ManagedPlugin {

    final PluginDescriptor descriptor;
    final Path jar;
    final ReentrantLock lock = new ReentrantLock();

    volatile Phase phase = Phase.DISCOVERED;
    volatile Throwable failureCause;
    volatile PluginClassLoader classLoader;
    volatile Plugin instance;

    ManagedPlugin(PluginDescriptor descriptor, Path jar) {
        this.descriptor = descriptor;
        this.jar = jar;
    }
}
