package com.defenestration1111111.plugin.core.classloader;

import com.defenestration1111111.plugin.api.Plugin;
import com.defenestration1111111.plugin.core.util.PluginJarBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class PluginClassLoaderTest {

    @TempDir
    Path tempDir;

    private final ClassLoader hostCl = getClass().getClassLoader();

    @Test
    void loadsPluginClassFromJar() throws Exception {
        Path jar = new PluginJarBuilder()
                .withDescriptor("a", "1.0", "com.example.A")
                .withCompiledClass("com.example.A", "package com.example; public class A {}")
                .buildAt(tempDir, "a.jar");

        try (var cl = newPluginClassLoader(jar)) {
            Class<?> c = cl.loadClass("com.example.A");

            assertThat(c.getClassLoader()).isSameAs(cl);
            assertThat(c.getName()).isEqualTo("com.example.A");
        }
    }

    @Test
    void exportedApiClassComesFromHost() throws Exception {
        Path jar = new PluginJarBuilder()
                .withDescriptor("a", "1.0", "com.example.A")
                .withCompiledClass("com.example.A", "package com.example; public class A {}")
                .buildAt(tempDir, "a.jar");

        try (var cl = newPluginClassLoader(jar)) {
            Class<?> apiPlugin = cl.loadClass("com.defenestration1111111.plugin.api.Plugin");

            assertThat(apiPlugin).isSameAs(Plugin.class);
            assertThat(apiPlugin.getClassLoader()).isSameAs(hostCl);
        }
    }

    @Test
    void javaLangClassesComeFromPlatform() throws Exception {
        Path jar = new PluginJarBuilder()
                .withDescriptor("a", "1.0", "com.example.A")
                .buildAt(tempDir, "a.jar");

        try (var cl = newPluginClassLoader(jar)) {
            assertThat(cl.loadClass("java.lang.String")).isSameAs(String.class);
            assertThat(cl.loadClass("java.util.HashMap")).isSameAs(java.util.HashMap.class);
        }
    }

    @Test
    void apiIdentitySharedAcrossPlugins() throws Exception {
        Path jarA = new PluginJarBuilder()
                .withDescriptor("a", "1.0", "com.example.A")
                .withCompiledClass("com.example.A", "package com.example; public class A {}")
                .buildAt(tempDir, "a.jar");
        Path jarB = new PluginJarBuilder()
                .withDescriptor("b", "1.0", "com.example.B")
                .withCompiledClass("com.example.B", "package com.example; public class B {}")
                .buildAt(tempDir, "b.jar");

        try (var clA = newPluginClassLoader(jarA);
             var clB = newPluginClassLoader(jarB)) {
            Class<?> apiFromA = clA.loadClass("com.defenestration1111111.plugin.api.Plugin");
            Class<?> apiFromB = clB.loadClass("com.defenestration1111111.plugin.api.Plugin");

            assertThat(apiFromA).isSameAs(apiFromB);
            assertThat(apiFromA).isSameAs(Plugin.class);
        }
    }

    @Test
    void privateClassesIsolatedAcrossPlugins() throws Exception {
        Path jarA = new PluginJarBuilder()
                .withDescriptor("a", "1.0", "com.example.Shared")
                .withCompiledClass("com.example.Shared",
                        "package com.example; public class Shared { public int a = 1; }")
                .buildAt(tempDir, "a.jar");
        Path jarB = new PluginJarBuilder()
                .withDescriptor("b", "1.0", "com.example.Shared")
                .withCompiledClass("com.example.Shared",
                        "package com.example; public class Shared { public int b = 2; }")
                .buildAt(tempDir, "b.jar");

        try (var clA = newPluginClassLoader(jarA);
             var clB = newPluginClassLoader(jarB)) {
            Class<?> sharedFromA = clA.loadClass("com.example.Shared");
            Class<?> sharedFromB = clB.loadClass("com.example.Shared");

            assertThat(sharedFromA).isNotSameAs(sharedFromB);
            assertThat(sharedFromA.getClassLoader()).isSameAs(clA);
            assertThat(sharedFromB.getClassLoader()).isSameAs(clB);
            assertThat(sharedFromA.getDeclaredFields()).hasSize(1);
            assertThat(sharedFromA.getDeclaredFields()[0].getName()).isEqualTo("a");
            assertThat(sharedFromB.getDeclaredFields()[0].getName()).isEqualTo("b");
        }
    }

    @Test
    void pluginCanImplementApiInterface() throws Exception {
        Path jar = new PluginJarBuilder()
                .withDescriptor("a", "1.0", "com.example.A")
                .withCompiledClass("com.example.A", """
                        package com.example;
                        import com.defenestration1111111.plugin.api.Plugin;
                        public class A implements Plugin {}
                        """)
                .buildAt(tempDir, "a.jar");

        try (var cl = newPluginClassLoader(jar)) {
            Class<?> aClass = cl.loadClass("com.example.A");

            assertThat(Plugin.class.isAssignableFrom(aClass)).isTrue();
            Object instance = aClass.getDeclaredConstructor().newInstance();
            assertThat(instance).isInstanceOf(Plugin.class);
        }
    }

    @Test
    void unknownClassThrowsClassNotFound() throws Exception {
        Path jar = new PluginJarBuilder()
                .withDescriptor("a", "1.0", "com.example.A")
                .buildAt(tempDir, "a.jar");

        try (var cl = newPluginClassLoader(jar)) {
            assertThatExceptionOfType(ClassNotFoundException.class)
                    .isThrownBy(() -> cl.loadClass("com.nonexistent.Bogus"));
        }
    }

    @Test
    void unexportedHostClassIsReachableViaFallback() throws Exception {
        Path jar = new PluginJarBuilder()
                .withDescriptor("a", "1.0", "com.example.A")
                .buildAt(tempDir, "a.jar");

        try (var cl = newPluginClassLoader(jar)) {
            Class<?> testClass = cl.loadClass(getClass().getName());

            assertThat(testClass).isSameAs(getClass());
        }
    }

    @Test
    void rejectsNullHostClassLoader() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PluginClassLoader(
                        new URL[0], null, PluginClassLoader.BASELINE_EXPORTED_PACKAGES));
    }

    @Test
    void rejectsNullExportedPackages() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PluginClassLoader(new URL[0], hostCl, null));
    }

    @Test
    void exposesExportedPackagesAsImmutable() {
        var cl = new PluginClassLoader(
                new URL[0], hostCl, PluginClassLoader.BASELINE_EXPORTED_PACKAGES);

        assertThat(cl.exportedPackages()).containsExactly("com.defenestration1111111.plugin.api");
        assertThat(cl.exportedPackages()).isUnmodifiable();
    }

    private PluginClassLoader newPluginClassLoader(Path jar) throws Exception {
        URL url = jar.toUri().toURL();
        return new PluginClassLoader(
                new URL[]{url}, hostCl, PluginClassLoader.BASELINE_EXPORTED_PACKAGES);
    }
}
