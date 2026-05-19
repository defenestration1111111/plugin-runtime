package com.defenestration1111111.plugin.core.discovery;

import com.defenestration1111111.plugin.api.PluginDescriptor;
import com.defenestration1111111.plugin.api.PluginDescriptorException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class DescriptorParserTest {

    @Test
    void parsesValidProperties() {
        var props = new Properties();
        props.setProperty("id", "acme");
        props.setProperty("version", "1.0.0");
        props.setProperty("mainClass", "com.acme.AcmePlugin");

        PluginDescriptor descriptor = DescriptorParser.parse(props);

        assertThat(descriptor.id()).isEqualTo("acme");
        assertThat(descriptor.version()).isEqualTo("1.0.0");
        assertThat(descriptor.mainClass()).isEqualTo("com.acme.AcmePlugin");
    }

    @Test
    void parsesFromInputStreamAsUtf8() throws IOException {
        String content = "id=acme\nversion=1.0.0\nmainClass=com.acme.AcmePlugin\n";
        InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        PluginDescriptor descriptor = DescriptorParser.parse(in);

        assertThat(descriptor.id()).isEqualTo("acme");
        assertThat(descriptor.mainClass()).isEqualTo("com.acme.AcmePlugin");
    }

    @Test
    void rejectsMissingId() {
        var props = new Properties();
        props.setProperty("version", "1.0.0");
        props.setProperty("mainClass", "com.acme.AcmePlugin");

        assertThatExceptionOfType(PluginDescriptorException.class)
                .isThrownBy(() -> DescriptorParser.parse(props))
                .withMessageContaining("Missing")
                .withMessageContaining("id");
    }

    @Test
    void rejectsMissingVersion() {
        var props = new Properties();
        props.setProperty("id", "acme");
        props.setProperty("mainClass", "com.acme.AcmePlugin");

        assertThatExceptionOfType(PluginDescriptorException.class)
                .isThrownBy(() -> DescriptorParser.parse(props))
                .withMessageContaining("Missing")
                .withMessageContaining("version");
    }

    @Test
    void rejectsMissingMainClass() {
        var props = new Properties();
        props.setProperty("id", "acme");
        props.setProperty("version", "1.0.0");

        assertThatExceptionOfType(PluginDescriptorException.class)
                .isThrownBy(() -> DescriptorParser.parse(props))
                .withMessageContaining("Missing")
                .withMessageContaining("mainClass");
    }

    @Test
    void listsAllMissingKeysInOneError() {
        var props = new Properties();

        assertThatExceptionOfType(PluginDescriptorException.class)
                .isThrownBy(() -> DescriptorParser.parse(props))
                .withMessageContaining("id")
                .withMessageContaining("version")
                .withMessageContaining("mainClass");
    }

    @Test
    void rejectsBlankValue() {
        var props = new Properties();
        props.setProperty("id", "");
        props.setProperty("version", "1.0.0");
        props.setProperty("mainClass", "com.acme.AcmePlugin");

        assertThatExceptionOfType(PluginDescriptorException.class)
                .isThrownBy(() -> DescriptorParser.parse(props))
                .withMessageContaining("id");
    }

    @Test
    void rejectsUnknownKey() {
        var props = new Properties();
        props.setProperty("id", "acme");
        props.setProperty("version", "1.0.0");
        props.setProperty("mainClass", "com.acme.AcmePlugin");
        props.setProperty("vendor", "Acme Corp");

        assertThatExceptionOfType(PluginDescriptorException.class)
                .isThrownBy(() -> DescriptorParser.parse(props))
                .withMessageContaining("Unknown")
                .withMessageContaining("vendor");
    }

    @Test
    void listsAllUnknownKeysInOneError() {
        var props = new Properties();
        props.setProperty("id", "acme");
        props.setProperty("version", "1.0.0");
        props.setProperty("mainClass", "com.acme.AcmePlugin");
        props.setProperty("vendor", "Acme Corp");
        props.setProperty("description", "...");

        assertThatExceptionOfType(PluginDescriptorException.class)
                .isThrownBy(() -> DescriptorParser.parse(props))
                .withMessageContaining("vendor")
                .withMessageContaining("description");
    }
}
