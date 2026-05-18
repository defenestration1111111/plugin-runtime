package com.defenestration1111111.plugin.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class PluginDescriptorTest {

    @Test
    void constructsWithValidValues() {
        var descriptor = new PluginDescriptor("acme", "1.0.0", "com.acme.AcmePlugin");

        assertThat(descriptor.id()).isEqualTo("acme");
        assertThat(descriptor.version()).isEqualTo("1.0.0");
        assertThat(descriptor.mainClass()).isEqualTo("com.acme.AcmePlugin");
    }

    @Test
    void rejectsNullId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PluginDescriptor(null, "1.0.0", "com.acme.AcmePlugin"))
                .withMessageContaining("id");
    }

    @Test
    void rejectsNullVersion() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PluginDescriptor("acme", null, "com.acme.AcmePlugin"))
                .withMessageContaining("version");
    }

    @Test
    void rejectsNullMainClass() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PluginDescriptor("acme", "1.0.0", null))
                .withMessageContaining("mainClass");
    }

    @Test
    void rejectsBlankId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PluginDescriptor("  ", "1.0.0", "com.acme.AcmePlugin"))
                .withMessageContaining("id");
    }

    @Test
    void rejectsBlankVersion() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PluginDescriptor("acme", "", "com.acme.AcmePlugin"))
                .withMessageContaining("version");
    }

    @Test
    void rejectsBlankMainClass() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PluginDescriptor("acme", "1.0.0", " "))
                .withMessageContaining("mainClass");
    }
}
