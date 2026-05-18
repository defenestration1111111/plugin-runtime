package com.defenestration1111111.plugin.api;

public interface PluginContext {

    PluginDescriptor descriptor();

    <T extends ExtensionPoint> void register(Class<T> extensionPoint, T extension);
}
