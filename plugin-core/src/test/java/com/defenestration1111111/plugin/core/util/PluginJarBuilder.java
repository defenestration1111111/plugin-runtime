package com.defenestration1111111.plugin.core.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class PluginJarBuilder {

    private final Map<String, byte[]> entries = new LinkedHashMap<>();

    public PluginJarBuilder withDescriptor(String id, String version, String mainClass) {
        Properties props = new Properties();
        if (id != null) props.setProperty("id", id);
        if (version != null) props.setProperty("version", version);
        if (mainClass != null) props.setProperty("mainClass", mainClass);
        return withDescriptor(props);
    }

    public PluginJarBuilder withDescriptor(Properties props) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            props.store(new OutputStreamWriter(buf, StandardCharsets.UTF_8), null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return withEntry("META-INF/plugin.properties", buf.toByteArray());
    }

    public PluginJarBuilder withEntry(String path, byte[] content) {
        entries.put(path, content);
        return this;
    }

    public PluginJarBuilder withEntry(String path, String content) {
        return withEntry(path, content.getBytes(StandardCharsets.UTF_8));
    }

    public Path buildAt(Path directory, String filename) throws IOException {
        Files.createDirectories(directory);
        Path jarPath = directory.resolve(filename);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                JarEntry entry = new JarEntry(e.getKey());
                jos.putNextEntry(entry);
                jos.write(e.getValue());
                jos.closeEntry();
            }
        }
        return jarPath;
    }
}
