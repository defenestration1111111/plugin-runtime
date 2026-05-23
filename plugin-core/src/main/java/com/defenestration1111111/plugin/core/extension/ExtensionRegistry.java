package com.defenestration1111111.plugin.core.extension;

import com.defenestration1111111.plugin.api.ExtensionPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ExtensionRegistry {

    private final Map<Class<?>, CopyOnWriteArrayList<Object>> byType = new ConcurrentHashMap<>();
    private final Map<String, List<Entry>> byPlugin = new ConcurrentHashMap<>();

    public <T extends ExtensionPoint> void register(String pluginId, Class<T> extensionPoint, T extension) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(extensionPoint, "extensionPoint");
        Objects.requireNonNull(extension, "extension");
        if (!extensionPoint.isInstance(extension)) {
            throw new IllegalArgumentException(
                    "Extension " + extension.getClass().getName()
                            + " is not assignable to extension point " + extensionPoint.getName()
                            + " — likely a classloader visibility issue (is the extension point package exported?)");
        }
        byType.computeIfAbsent(extensionPoint, k -> new CopyOnWriteArrayList<>()).add(extension);
        List<Entry> entries = byPlugin.computeIfAbsent(pluginId, k -> new ArrayList<>());
        synchronized (entries) {
            entries.add(new Entry(extensionPoint, extension));
        }
    }

    public void unregisterAll(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        List<Entry> entries = byPlugin.remove(pluginId);
        if (entries == null) return;
        List<Entry> snapshot;
        synchronized (entries) {
            snapshot = List.copyOf(entries);
        }
        for (Entry e : snapshot) {
            CopyOnWriteArrayList<Object> list = byType.get(e.type);
            if (list == null) continue;
            list.removeIf(x -> x == e.instance);
        }
    }

    public <T extends ExtensionPoint> List<T> getExtensions(Class<T> extensionPoint) {
        Objects.requireNonNull(extensionPoint, "extensionPoint");
        CopyOnWriteArrayList<Object> list = byType.get(extensionPoint);
        if (list == null || list.isEmpty()) return List.of();
        List<T> result = new ArrayList<>(list.size());
        for (Object o : list) {
            result.add(extensionPoint.cast(o));
        }
        return List.copyOf(result);
    }

    private record Entry(Class<?> type, Object instance) {
    }
}
