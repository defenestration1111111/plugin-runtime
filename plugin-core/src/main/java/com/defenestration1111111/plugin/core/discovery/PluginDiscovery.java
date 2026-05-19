package com.defenestration1111111.plugin.core.discovery;

import com.defenestration1111111.plugin.api.PluginDescriptor;
import com.defenestration1111111.plugin.api.PluginDescriptorException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class PluginDiscovery {

    public DiscoveryReport scan(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Not a directory: " + directory);
        }

        List<Path> jars = listJars(directory);
        List<DiscoveredPlugin> plugins = new ArrayList<>();
        List<DiscoveryFailure> failures = new ArrayList<>();
        Map<String, Path> firstByPluginId = new HashMap<>();

        for (Path jar : jars) {
            try {
                PluginDescriptor descriptor = readDescriptor(jar);
                Path firstSeen = firstByPluginId.get(descriptor.id());
                if (firstSeen != null) {
                    failures.add(new DiscoveryFailure(jar, new PluginDescriptorException(
                            "Duplicate plugin id '" + descriptor.id()
                                    + "', first declared in " + firstSeen)));
                } else {
                    firstByPluginId.put(descriptor.id(), jar);
                    plugins.add(new DiscoveredPlugin(jar, descriptor));
                }
            } catch (PluginDescriptorException e) {
                failures.add(new DiscoveryFailure(jar, e));
            }
        }

        return new DiscoveryReport(plugins, failures);
    }

    private static List<Path> listJars(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list directory: " + directory, e);
        }
    }

    private static PluginDescriptor readDescriptor(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(DescriptorParser.DESCRIPTOR_PATH);
            if (entry == null) {
                throw new PluginDescriptorException(
                        "Missing " + DescriptorParser.DESCRIPTOR_PATH + " in " + jarPath);
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return DescriptorParser.parse(in);
            }
        } catch (IOException e) {
            throw new PluginDescriptorException(
                    "Failed to read descriptor from " + jarPath + ": " + e.getMessage(), e);
        }
    }
}
