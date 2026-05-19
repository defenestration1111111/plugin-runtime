package com.defenestration1111111.plugin.core.discovery;

import com.defenestration1111111.plugin.api.PluginDescriptorException;

import java.nio.file.Path;
import java.util.Objects;

public record DiscoveryFailure(Path jar, PluginDescriptorException error) {

    public DiscoveryFailure {
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(error, "error");
    }
}
