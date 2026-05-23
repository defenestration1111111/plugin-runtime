package com.defenestration1111111.plugin.core.lifecycle;

import com.defenestration1111111.plugin.api.ExtensionPoint;
import com.defenestration1111111.plugin.api.PluginContext;
import com.defenestration1111111.plugin.api.PluginDescriptor;
import com.defenestration1111111.plugin.core.extension.ExtensionRegistry;

import java.util.Objects;

final class DefaultPluginContext implements PluginContext {

    private final PluginDescriptor descriptor;
    private final ExtensionRegistry registry;

    DefaultPluginContext(PluginDescriptor descriptor, ExtensionRegistry registry) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public PluginDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public <T extends ExtensionPoint> void register(Class<T> extensionPoint, T extension) {
        registry.register(descriptor().id(), extensionPoint, extension);
    }
}
