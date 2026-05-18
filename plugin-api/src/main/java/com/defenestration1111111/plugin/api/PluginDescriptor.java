package com.defenestration1111111.plugin.api;

import java.util.Objects;

public record PluginDescriptor(String id, String version, String mainClass) {

    public PluginDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(mainClass, "mainClass");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (mainClass.isBlank()) {
            throw new IllegalArgumentException("mainClass must not be blank");
        }
    }
}
