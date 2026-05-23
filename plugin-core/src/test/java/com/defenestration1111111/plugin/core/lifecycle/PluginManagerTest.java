package com.defenestration1111111.plugin.core.lifecycle;

import com.defenestration1111111.plugin.api.PluginDescriptor;
import com.defenestration1111111.plugin.api.PluginLifecycleException;
import com.defenestration1111111.plugin.api.PluginLoadException;
import com.defenestration1111111.plugin.core.classloader.PluginClassLoader;
import com.defenestration1111111.plugin.core.discovery.DiscoveredPlugin;
import com.defenestration1111111.plugin.core.util.PluginJarBuilder;
import com.defenestration1111111.plugin.core.util.TestGreeter;
import com.defenestration1111111.plugin.core.util.TestRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginManagerTest {

    private static final String NORMAL_PLUGIN_BODY = """
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

    @TempDir
    Path tempDir;

    private final ClassLoader hostCl = getClass().getClassLoader();
    private PluginManager manager;

    @BeforeEach
    void setUp() {
        TestRecorder.reset();
        manager = new PluginManager(hostCl);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    // ---- happy path ----

    @Test
    void fullLifecycleHappyPath() throws Exception {
        manager.install(normalPlugin("happy"));

        assertThat(manager.phase("happy")).isEqualTo(Phase.DISCOVERED);
        manager.resolve("happy");
        assertThat(manager.phase("happy")).isEqualTo(Phase.RESOLVED);
        manager.load("happy");
        assertThat(manager.phase("happy")).isEqualTo(Phase.LOADED);
        manager.start("happy");
        assertThat(manager.phase("happy")).isEqualTo(Phase.STARTED);

        assertThat(TestRecorder.startCount.get()).isEqualTo(1);
        var cl = (PluginClassLoader) TestRecorder.classLoaders.get("happy");
        assertThat(cl).isNotNull();
        assertThat(cl.isClosed()).isFalse();

        manager.stop("happy");
        assertThat(manager.phase("happy")).isEqualTo(Phase.STOPPED);
        assertThat(TestRecorder.stopCount.get()).isEqualTo(1);

        manager.unload("happy");
        assertThat(manager.phase("happy")).isEqualTo(Phase.UNLOADED);
        assertThat(cl.isClosed()).isTrue();
    }

    // ---- install / discovery ----

    @Test
    void installRejectsDuplicateId() throws Exception {
        manager.install(normalPlugin("dup"));
        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.install(normalPlugin("dup")));
    }

    @Test
    void unknownIdRejectedByOps() {
        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.phase("nope"));
        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.start("nope"));
    }

    @Test
    void idsListsAllInstalled() throws Exception {
        manager.install(normalPlugin("a"));
        manager.install(normalPlugin("b"));
        assertThat(manager.ids()).containsExactlyInAnyOrder("a", "b");
    }

    // ---- transition guards ----

    @Test
    void loadBeforeResolveThrows() throws Exception {
        manager.install(normalPlugin("p1"));
        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.load("p1"));
    }

    @Test
    void startBeforeLoadThrows() throws Exception {
        manager.install(normalPlugin("p1"));
        manager.resolve("p1");
        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.start("p1"));
    }

    @Test
    void startTwiceThrows() throws Exception {
        manager.install(normalPlugin("p1"));
        boot("p1");
        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.start("p1"));
    }

    @Test
    void stopWhenNotStartedThrows() throws Exception {
        manager.install(normalPlugin("p1"));
        manager.resolve("p1");
        manager.load("p1");
        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.stop("p1"));
    }

    @Test
    void unloadFromUnsupportedPhasesThrows() throws Exception {
        manager.install(normalPlugin("p1"));
        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.unload("p1"));
        manager.resolve("p1");
        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.unload("p1"));
        manager.load("p1");
        manager.start("p1");
        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.unload("p1"));
    }

    @Test
    void unloadFromLoadedSucceeds() throws Exception {
        manager.install(normalPlugin("p1"));
        manager.resolve("p1");
        manager.load("p1");
        manager.unload("p1");
        assertThat(manager.phase("p1")).isEqualTo(Phase.UNLOADED);
        assertThat(TestRecorder.startCount.get()).isZero();
        assertThat(TestRecorder.stopCount.get()).isZero();
    }

    @Test
    void unloadedIsTerminalForOps() throws Exception {
        manager.install(normalPlugin("p1"));
        manager.resolve("p1");
        manager.load("p1");
        manager.unload("p1");

        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.start("p1"));
        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.unload("p1"));
    }

    @Test
    void failedIsTerminalForAllOps() throws Exception {
        manager.install(missingMainClassPlugin("bad"));
        manager.resolve("bad");
        assertThatExceptionOfType(PluginLoadException.class)
                .isThrownBy(() -> manager.load("bad"));
        assertThat(manager.phase("bad")).isEqualTo(Phase.FAILED);

        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.resolve("bad"));
        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.load("bad"));
        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.start("bad"));
        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.stop("bad"));
        assertThatExceptionOfType(PluginLifecycleException.class)
                .isThrownBy(() -> manager.unload("bad"));
    }

    // ---- load failures ----

    @Test
    void mainClassNotFoundFails() throws Exception {
        manager.install(missingMainClassPlugin("nofind"));
        manager.resolve("nofind");

        assertThatThrownBy(() -> manager.load("nofind"))
                .isInstanceOf(PluginLoadException.class)
                .hasMessageContaining("Main class not found");
        assertThat(manager.phase("nofind")).isEqualTo(Phase.FAILED);
        assertThat(manager.failure("nofind")).isPresent();
    }

    @Test
    void mainClassDoesNotImplementPluginFails() throws Exception {
        manager.install(pluginWith("notplug", "p.A",
                "package p; public class A {}"));
        manager.resolve("notplug");

        assertThatThrownBy(() -> manager.load("notplug"))
                .isInstanceOf(PluginLoadException.class)
                .hasMessageContaining("does not implement Plugin");
        assertThat(manager.phase("notplug")).isEqualTo(Phase.FAILED);
    }

    @Test
    void mainClassWithoutNoArgCtorFails() throws Exception {
        manager.install(pluginWith("noctor", "p.A", """
                package p;
                import com.defenestration1111111.plugin.api.Plugin;
                public class A implements Plugin {
                    public A(int x) {}
                }
                """));
        manager.resolve("noctor");

        assertThatThrownBy(() -> manager.load("noctor"))
                .isInstanceOf(PluginLoadException.class)
                .hasMessageContaining("no public no-arg constructor");
    }

    @Test
    void constructorThrowsCausesFail() throws Exception {
        manager.install(pluginWith("ctorboom", "p.A", """
                package p;
                import com.defenestration1111111.plugin.api.Plugin;
                public class A implements Plugin {
                    public A() { throw new RuntimeException("ctor boom"); }
                }
                """));
        manager.resolve("ctorboom");

        assertThatThrownBy(() -> manager.load("ctorboom"))
                .isInstanceOf(PluginLoadException.class)
                .hasMessageContaining("constructor threw")
                .hasRootCauseMessage("ctor boom");
        assertThat(manager.phase("ctorboom")).isEqualTo(Phase.FAILED);
    }

    @Test
    void startThrowsClosesClassLoaderAndFails() throws Exception {
        manager.install(pluginWith("startboom", "p.A", """
                package p;
                import com.defenestration1111111.plugin.api.Plugin;
                import com.defenestration1111111.plugin.api.PluginContext;
                import com.defenestration1111111.plugin.core.util.TestRecorder;
                public class A implements Plugin {
                    @Override public void start(PluginContext ctx) {
                        TestRecorder.classLoaders.put(ctx.descriptor().id(), getClass().getClassLoader());
                        throw new RuntimeException("start boom");
                    }
                }
                """));
        manager.resolve("startboom");
        manager.load("startboom");

        assertThatThrownBy(() -> manager.start("startboom"))
                .hasMessage("start boom");
        assertThat(manager.phase("startboom")).isEqualTo(Phase.FAILED);
        var cl = (PluginClassLoader) TestRecorder.classLoaders.get("startboom");
        assertThat(cl.isClosed()).isTrue();
    }

    @Test
    void stopThrowsClosesClassLoaderAndFails() throws Exception {
        manager.install(pluginWith("stopboom", "p.A", """
                package p;
                import com.defenestration1111111.plugin.api.Plugin;
                import com.defenestration1111111.plugin.api.PluginContext;
                import com.defenestration1111111.plugin.core.util.TestRecorder;
                public class A implements Plugin {
                    @Override public void start(PluginContext ctx) {
                        TestRecorder.recordStart(ctx.descriptor().id(), getClass().getClassLoader());
                    }
                    @Override public void stop() {
                        throw new RuntimeException("stop boom");
                    }
                }
                """));
        boot("stopboom");
        var cl = (PluginClassLoader) TestRecorder.classLoaders.get("stopboom");

        assertThatThrownBy(() -> manager.stop("stopboom"))
                .hasMessage("stop boom");
        assertThat(manager.phase("stopboom")).isEqualTo(Phase.FAILED);
        assertThat(cl.isClosed()).isTrue();
    }

    // ---- concurrency ----

    @Test
    void concurrentStartCallsResultInOneInvocation() throws Exception {
        manager.install(normalPlugin("conc"));
        manager.resolve("conc");
        manager.load("conc");

        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch fire = new CountDownLatch(1);

            Future<Throwable> a = ex.submit(() -> {
                ready.countDown();
                fire.await();
                try { manager.start("conc"); return null; }
                catch (Throwable t) { return t; }
            });
            Future<Throwable> b = ex.submit(() -> {
                ready.countDown();
                fire.await();
                try { manager.start("conc"); return null; }
                catch (Throwable t) { return t; }
            });

            ready.await();
            fire.countDown();

            Throwable ra = a.get(5, TimeUnit.SECONDS);
            Throwable rb = b.get(5, TimeUnit.SECONDS);

            assertThat(TestRecorder.startCount.get()).isEqualTo(1);
            int successes = (ra == null ? 1 : 0) + (rb == null ? 1 : 0);
            assertThat(successes).isEqualTo(1);
            Throwable failure = ra != null ? ra : rb;
            assertThat(failure).isInstanceOf(PluginLifecycleException.class);
        } finally {
            ex.shutdownNow();
        }
    }

    // ---- batch ops ----

    @Test
    void startAllContinuesAfterPerPluginFailure() throws Exception {
        manager.install(normalPlugin("good1"));
        manager.install(missingMainClassPlugin("bad"));
        manager.install(normalPlugin("good2"));

        manager.startAll();

        assertThat(manager.phase("good1")).isEqualTo(Phase.STARTED);
        assertThat(manager.phase("bad")).isEqualTo(Phase.FAILED);
        assertThat(manager.phase("good2")).isEqualTo(Phase.STARTED);
        assertThat(TestRecorder.startCount.get()).isEqualTo(2);
        assertThat(manager.failure("bad")).isPresent();
    }

    @Test
    void closeStopsAndUnloadsAll() throws Exception {
        manager.install(normalPlugin("a"));
        manager.install(normalPlugin("b"));
        manager.startAll();

        var clA = (PluginClassLoader) TestRecorder.classLoaders.get("a");
        var clB = (PluginClassLoader) TestRecorder.classLoaders.get("b");

        manager.close();

        assertThat(manager.phase("a")).isEqualTo(Phase.UNLOADED);
        assertThat(manager.phase("b")).isEqualTo(Phase.UNLOADED);
        assertThat(TestRecorder.stopCount.get()).isEqualTo(2);
        assertThat(clA.isClosed()).isTrue();
        assertThat(clB.isClosed()).isTrue();
    }

    // ---- extensions ----

    private static final String GREETER_PLUGIN_BODY = """
            package p;
            import com.defenestration1111111.plugin.api.Plugin;
            import com.defenestration1111111.plugin.api.PluginContext;
            import com.defenestration1111111.plugin.core.util.TestGreeter;
            public class A implements Plugin, TestGreeter {
                private final String word;
                public A() { this.word = "hello"; }
                @Override public void start(PluginContext ctx) {
                    ctx.register(TestGreeter.class, this);
                }
                @Override public void stop() {}
                @Override public String greet() { return word; }
            }
            """;

    @Test
    void pluginRegistrationsAreVisibleAfterStart() throws Exception {
        manager.install(pluginWith("g1", "p.A", GREETER_PLUGIN_BODY));
        boot("g1");

        List<TestGreeter> greeters = manager.getExtensions(TestGreeter.class);
        assertThat(greeters).hasSize(1);
        assertThat(greeters.get(0).greet()).isEqualTo("hello");
    }

    @Test
    void getExtensionsForUnknownPointIsEmpty() {
        assertThat(manager.getExtensions(TestGreeter.class)).isEmpty();
    }

    @Test
    void stopRemovesPluginRegistrations() throws Exception {
        manager.install(pluginWith("g1", "p.A", GREETER_PLUGIN_BODY));
        boot("g1");
        assertThat(manager.getExtensions(TestGreeter.class)).hasSize(1);

        manager.stop("g1");
        assertThat(manager.getExtensions(TestGreeter.class)).isEmpty();
    }

    @Test
    void multiplePluginsContributeAndStopRemovesOnlyOne() throws Exception {
        manager.install(pluginWith("g1", "p.A", GREETER_PLUGIN_BODY));
        manager.install(pluginWith("g2", "p.A", GREETER_PLUGIN_BODY));
        boot("g1");
        boot("g2");

        assertThat(manager.getExtensions(TestGreeter.class)).hasSize(2);

        manager.stop("g1");
        List<TestGreeter> remaining = manager.getExtensions(TestGreeter.class);
        assertThat(remaining).hasSize(1);
    }

    @Test
    void failedStartLeavesNoStaleRegistrations() throws Exception {
        // Plugin registers an extension, then throws — start must roll back the registration.
        manager.install(pluginWith("partial", "p.A", """
                package p;
                import com.defenestration1111111.plugin.api.Plugin;
                import com.defenestration1111111.plugin.api.PluginContext;
                import com.defenestration1111111.plugin.core.util.TestGreeter;
                public class A implements Plugin, TestGreeter {
                    @Override public void start(PluginContext ctx) {
                        ctx.register(TestGreeter.class, this);
                        throw new RuntimeException("boom");
                    }
                    @Override public void stop() {}
                    @Override public String greet() { return "x"; }
                }
                """));
        manager.resolve("partial");
        manager.load("partial");

        assertThatThrownBy(() -> manager.start("partial")).hasMessage("boom");
        assertThat(manager.phase("partial")).isEqualTo(Phase.FAILED);
        assertThat(manager.getExtensions(TestGreeter.class)).isEmpty();
    }

    @Test
    void closeClearsAllRegistrations() throws Exception {
        manager.install(pluginWith("g1", "p.A", GREETER_PLUGIN_BODY));
        manager.install(pluginWith("g2", "p.A", GREETER_PLUGIN_BODY));
        manager.startAll();
        assertThat(manager.getExtensions(TestGreeter.class)).hasSize(2);

        manager.close();
        assertThat(manager.getExtensions(TestGreeter.class)).isEmpty();
    }

    // ---- helpers ----

    private DiscoveredPlugin pluginWith(String id, String fqn, String body) throws Exception {
        Path jar = new PluginJarBuilder()
                .withDescriptor(id, "1.0", fqn)
                .withCompiledClass(fqn, body)
                .buildAt(tempDir, id + ".jar");
        return new DiscoveredPlugin(jar, new PluginDescriptor(id, "1.0", fqn));
    }

    private DiscoveredPlugin normalPlugin(String id) throws Exception {
        return pluginWith(id, "p.A", NORMAL_PLUGIN_BODY);
    }

    private DiscoveredPlugin missingMainClassPlugin(String id) throws Exception {
        Path jar = new PluginJarBuilder()
                .withDescriptor(id, "1.0", "p.NotPresent")
                .withCompiledClass("p.Other", "package p; public class Other {}")
                .buildAt(tempDir, id + ".jar");
        return new DiscoveredPlugin(jar, new PluginDescriptor(id, "1.0", "p.NotPresent"));
    }

    private void boot(String id) {
        manager.resolve(id);
        manager.load(id);
        manager.start(id);
    }
}
