package com.defenestration1111111.plugin.core.discovery;

import com.defenestration1111111.plugin.core.util.PluginJarBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PluginDiscoveryTest {

    @TempDir
    Path tempDir;

    private final PluginDiscovery discovery = new PluginDiscovery();

    @Test
    void emptyDirectoryYieldsEmptyReport() {
        var report = discovery.scan(tempDir);

        assertThat(report.plugins()).isEmpty();
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void oneValidJarYieldsOneSuccess() throws Exception {
        Path jar = new PluginJarBuilder()
                .withDescriptor("acme", "1.0.0", "com.acme.AcmePlugin")
                .buildAt(tempDir, "acme.jar");

        var report = discovery.scan(tempDir);

        assertThat(report.plugins()).hasSize(1);
        DiscoveredPlugin p = report.plugins().get(0);
        assertThat(p.jar()).isEqualTo(jar);
        assertThat(p.descriptor().id()).isEqualTo("acme");
        assertThat(p.descriptor().version()).isEqualTo("1.0.0");
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void jarWithoutDescriptorYieldsFailure() throws Exception {
        Path jar = new PluginJarBuilder()
                .withEntry("README.txt", "no descriptor here")
                .buildAt(tempDir, "noplugin.jar");

        var report = discovery.scan(tempDir);

        assertThat(report.plugins()).isEmpty();
        assertThat(report.failures()).hasSize(1);
        assertThat(report.failures().get(0).jar()).isEqualTo(jar);
        assertThat(report.failures().get(0).error())
                .hasMessageContaining("Missing")
                .hasMessageContaining("plugin.properties");
    }

    @Test
    void jarWithMalformedDescriptorYieldsFailure() throws Exception {
        Path jar = new PluginJarBuilder()
                .withEntry("META-INF/plugin.properties", "id=acme\nbadkey=oops\n")
                .buildAt(tempDir, "bad.jar");

        var report = discovery.scan(tempDir);

        assertThat(report.plugins()).isEmpty();
        assertThat(report.failures()).hasSize(1);
        assertThat(report.failures().get(0).jar()).isEqualTo(jar);
        assertThat(report.failures().get(0).error())
                .hasMessageContaining("Unknown");
    }

    @Test
    void duplicateIdsKeepFirstFailRest() throws Exception {
        new PluginJarBuilder()
                .withDescriptor("acme", "1.0.0", "com.acme.AcmePlugin")
                .buildAt(tempDir, "a-acme.jar");
        new PluginJarBuilder()
                .withDescriptor("acme", "2.0.0", "com.acme.AcmePlugin")
                .buildAt(tempDir, "b-acme.jar");

        var report = discovery.scan(tempDir);

        assertThat(report.plugins()).hasSize(1);
        assertThat(report.plugins().get(0).jar().getFileName().toString()).isEqualTo("a-acme.jar");
        assertThat(report.plugins().get(0).descriptor().version()).isEqualTo("1.0.0");

        assertThat(report.failures()).hasSize(1);
        assertThat(report.failures().get(0).jar().getFileName().toString()).isEqualTo("b-acme.jar");
        assertThat(report.failures().get(0).error())
                .hasMessageContaining("Duplicate")
                .hasMessageContaining("a-acme.jar");
    }

    @Test
    void mixedValidAndInvalidPartitionedCorrectly() throws Exception {
        new PluginJarBuilder()
                .withDescriptor("a", "1.0.0", "com.a.Plugin")
                .buildAt(tempDir, "a.jar");
        new PluginJarBuilder()
                .withDescriptor("b", "1.0.0", "com.b.Plugin")
                .buildAt(tempDir, "b.jar");
        new PluginJarBuilder()
                .withEntry("README.txt", "no plugin")
                .buildAt(tempDir, "z.jar");

        var report = discovery.scan(tempDir);

        assertThat(report.plugins()).hasSize(2);
        assertThat(report.failures()).hasSize(1);
    }

    @Test
    void ignoresNonJarFiles() throws Exception {
        new PluginJarBuilder()
                .withDescriptor("a", "1.0.0", "com.a.Plugin")
                .buildAt(tempDir, "a.jar");
        Files.writeString(tempDir.resolve("README.md"), "ignore me");
        Files.writeString(tempDir.resolve("a.txt"), "ignore me too");

        var report = discovery.scan(tempDir);

        assertThat(report.plugins()).hasSize(1);
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void rejectsNonExistentDirectory() {
        Path nonexistent = tempDir.resolve("does-not-exist");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> discovery.scan(nonexistent));
    }

    @Test
    void rejectsFileInsteadOfDirectory() throws Exception {
        Path file = tempDir.resolve("a-file.txt");
        Files.writeString(file, "hello");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> discovery.scan(file));
    }
}
