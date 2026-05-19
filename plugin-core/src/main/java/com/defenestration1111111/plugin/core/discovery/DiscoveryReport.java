package com.defenestration1111111.plugin.core.discovery;

import java.util.List;
import java.util.Objects;

public record DiscoveryReport(List<DiscoveredPlugin> plugins, List<DiscoveryFailure> failures) {

    public DiscoveryReport {
        Objects.requireNonNull(plugins, "plugins");
        Objects.requireNonNull(failures, "failures");
        plugins = List.copyOf(plugins);
        failures = List.copyOf(failures);
    }
}
