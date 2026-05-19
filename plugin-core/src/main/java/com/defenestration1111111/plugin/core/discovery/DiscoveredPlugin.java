package com.defenestration1111111.plugin.core.discovery;

import com.defenestration1111111.plugin.api.PluginDescriptor;

import java.nio.file.Path;
import java.util.Objects;

public record DiscoveredPlugin(Path jar, PluginDescriptor descriptor) {

    public DiscoveredPlugin {
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(descriptor, "descriptor");
    }
}
