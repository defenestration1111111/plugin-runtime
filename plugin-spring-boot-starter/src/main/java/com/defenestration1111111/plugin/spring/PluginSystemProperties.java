package com.defenestration1111111.plugin.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties(prefix = "plugins")
public class PluginSystemProperties {

    private boolean enabled = true;
    private Path directory;
    private boolean watch = false;
    private boolean autoStart = true;
    private Set<String> exportedPackages = new LinkedHashSet<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Path getDirectory() {
        return directory;
    }

    public void setDirectory(Path directory) {
        this.directory = directory;
    }

    public boolean isWatch() {
        return watch;
    }

    public void setWatch(boolean watch) {
        this.watch = watch;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }

    public Set<String> getExportedPackages() {
        return exportedPackages;
    }

    public void setExportedPackages(Set<String> exportedPackages) {
        this.exportedPackages = exportedPackages;
    }
}
