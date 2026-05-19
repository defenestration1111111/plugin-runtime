package com.defenestration1111111.plugin.core.discovery;

import com.defenestration1111111.plugin.api.PluginDescriptor;
import com.defenestration1111111.plugin.api.PluginDescriptorException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public final class DescriptorParser {

    public static final String DESCRIPTOR_PATH = "META-INF/plugin.properties";

    public static final String KEY_ID = "id";
    public static final String KEY_VERSION = "version";
    public static final String KEY_MAIN_CLASS = "mainClass";

    private static final Set<String> KNOWN_KEYS = Set.of(KEY_ID, KEY_VERSION, KEY_MAIN_CLASS);

    private DescriptorParser() {
    }

    public static PluginDescriptor parse(InputStream in) throws IOException {
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        Properties props = new Properties();
        props.load(reader);
        return parse(props);
    }

    public static PluginDescriptor parse(Properties props) {
        List<String> unknown = props.keySet().stream()
                .map(Object::toString)
                .filter(k -> !KNOWN_KEYS.contains(k))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw new PluginDescriptorException("Unknown descriptor keys: " + unknown);
        }

        List<String> missing = KNOWN_KEYS.stream()
                .filter(k -> props.getProperty(k) == null)
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new PluginDescriptorException("Missing required descriptor keys: " + missing);
        }

        try {
            return new PluginDescriptor(
                    props.getProperty(KEY_ID),
                    props.getProperty(KEY_VERSION),
                    props.getProperty(KEY_MAIN_CLASS));
        } catch (IllegalArgumentException e) {
            throw new PluginDescriptorException("Invalid descriptor value: " + e.getMessage(), e);
        }
    }
}
