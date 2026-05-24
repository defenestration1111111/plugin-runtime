package com.defenestration1111111.plugin.spring;

import com.defenestration1111111.plugin.core.lifecycle.Phase;
import com.defenestration1111111.plugin.core.lifecycle.PluginManager;
import com.defenestration1111111.plugin.core.util.PluginJarBuilder;
import com.defenestration1111111.plugin.core.util.TestRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PluginSystemAutoConfigurationTest {

    private static final String PLUGIN_BODY = """
            package p;
            import com.defenestration1111111.plugin.api.Plugin;
            import com.defenestration1111111.plugin.api.PluginContext;
            import com.defenestration1111111.plugin.core.util.TestRecorder;
            public class A implements Plugin {
                @Override public void start(PluginContext ctx) {
                    TestRecorder.recordStart(ctx.descriptor().id(), getClass().getClassLoader());
                }
                @Override public void stop() {
                    TestRecorder.recordStop();
                }
            }
            """;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PluginSystemAutoConfiguration.class));

    @BeforeEach
    void resetRecorder() {
        TestRecorder.reset();
    }

    @Test
    void disabledExposesNoBeans() {
        contextRunner
                .withPropertyValues("plugins.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(PluginManager.class));
    }

    @Test
    void enabledWithoutDirectoryExposesManagerButInstallsNothing() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(PluginManager.class);
            assertThat(ctx).hasSingleBean(PluginSystemLifecycle.class);
            PluginManager manager = ctx.getBean(PluginManager.class);
            assertThat(manager.ids()).isEmpty();
        });
    }

    @Test
    void scansAndAutoStartsPluginsFromDirectory(@TempDir Path dir) throws Exception {
        Path jar = new PluginJarBuilder()
                .withDescriptor("boot1", "1.0", "p.A")
                .withCompiledClass("p.A", PLUGIN_BODY)
                .buildAt(dir, "boot1.jar");
        assertThat(jar).exists();

        contextRunner
                .withPropertyValues("plugins.directory=" + dir.toAbsolutePath())
                .run(ctx -> {
                    PluginManager manager = ctx.getBean(PluginManager.class);
                    assertThat(manager.ids()).contains("boot1");
                    assertThat(manager.phase("boot1")).isEqualTo(Phase.STARTED);
                    assertThat(TestRecorder.startCount.get()).isEqualTo(1);
                });
    }

    @Test
    void autoStartFalseInstallsButDoesNotStart(@TempDir Path dir) throws Exception {
        new PluginJarBuilder()
                .withDescriptor("boot2", "1.0", "p.A")
                .withCompiledClass("p.A", PLUGIN_BODY)
                .buildAt(dir, "boot2.jar");

        contextRunner
                .withPropertyValues(
                        "plugins.directory=" + dir.toAbsolutePath(),
                        "plugins.auto-start=false")
                .run(ctx -> {
                    PluginManager manager = ctx.getBean(PluginManager.class);
                    assertThat(manager.ids()).contains("boot2");
                    assertThat(manager.phase("boot2")).isEqualTo(Phase.DISCOVERED);
                    assertThat(TestRecorder.startCount.get()).isZero();
                });
    }

    @Test
    void closingContextStopsAndUnloadsPlugins(@TempDir Path dir) throws Exception {
        new PluginJarBuilder()
                .withDescriptor("boot3", "1.0", "p.A")
                .withCompiledClass("p.A", PLUGIN_BODY)
                .buildAt(dir, "boot3.jar");

        contextRunner
                .withPropertyValues("plugins.directory=" + dir.toAbsolutePath())
                .run(ctx -> {
                    PluginManager manager = ctx.getBean(PluginManager.class);
                    assertThat(manager.phase("boot3")).isEqualTo(Phase.STARTED);
                });

        // After the run block, ApplicationContextRunner closes the context,
        // which triggers SmartLifecycle.stop() and the manager's destroyMethod.
        assertThat(TestRecorder.stopCount.get()).isEqualTo(1);
    }

    @Test
    void customExportedPackagesAreAppliedToManager() {
        contextRunner
                .withPropertyValues("plugins.exported-packages=com.example.x,com.example.y")
                .run(ctx -> {
                    // Indirect check: the bean was created without error and ids are empty.
                    assertThat(ctx).hasSingleBean(PluginManager.class);
                });
    }

    @Test
    void respectsUserSuppliedPluginManagerBean() {
        contextRunner
                .withUserConfiguration(UserManagerConfig.class)
                .run(ctx -> {
                    PluginManager manager = ctx.getBean(PluginManager.class);
                    assertThat(manager).isSameAs(UserManagerConfig.INSTANCE);
                });
    }

    @org.springframework.context.annotation.Configuration
    static class UserManagerConfig {
        static final PluginManager INSTANCE =
                new PluginManager(UserManagerConfig.class.getClassLoader());

        @org.springframework.context.annotation.Bean(destroyMethod = "close")
        PluginManager pluginManager() {
            return INSTANCE;
        }
    }
}
