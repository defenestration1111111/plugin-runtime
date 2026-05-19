package com.defenestration1111111.plugin.core.lifecycle;

import com.defenestration1111111.plugin.api.ExtensionPoint;
import com.defenestration1111111.plugin.api.PluginContext;
import com.defenestration1111111.plugin.api.PluginDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class DefaultPluginContext implements PluginContext {

    private final PluginDescriptor descriptor;
    private final Map<Class<?>, List<Object>> registrations = new ConcurrentHashMap<>();

    DefaultPluginContext(PluginDescriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    @Override
    public PluginDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public <T extends ExtensionPoint> void register(Class<T> extensionPoint, T extension) {
        Objects.requireNonNull(extensionPoint, "extensionPoint");
        Objects.requireNonNull(extension, "extension");
        registrations.computeIfAbsent(extensionPoint, k -> new ArrayList<>()).add(extension);
    }

    Map<Class<?>, List<Object>> registrations() {
        return registrations;
    }
}
