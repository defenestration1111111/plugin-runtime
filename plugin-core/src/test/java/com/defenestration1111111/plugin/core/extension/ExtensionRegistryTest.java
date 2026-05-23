package com.defenestration1111111.plugin.core.extension;

import com.defenestration1111111.plugin.api.ExtensionPoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionRegistryTest {

    interface Greeter extends ExtensionPoint {
        String greet();
    }

    interface Farewell extends ExtensionPoint {
        String bye();
    }

    static final class HelloGreeter implements Greeter {
        @Override public String greet() {
            return "hello";
        }
    }

    static final class HiGreeter implements Greeter {
        @Override public String greet() {
            return "hi";
        }
    }

    static final class ByeFarewell implements Farewell {
        @Override public String bye() {
            return "bye";
        }
    }

    @Test
    void registersAndReturnsExtensions() {
        ExtensionRegistry r = new ExtensionRegistry();
        HelloGreeter hello = new HelloGreeter();
        HiGreeter hi = new HiGreeter();

        r.register("a", Greeter.class, hello);
        r.register("a", Greeter.class, hi);

        List<Greeter> greeters = r.getExtensions(Greeter.class);
        assertThat(greeters).containsExactly(hello, hi);
    }

    @Test
    void getExtensionsForUnknownTypeIsEmpty() {
        ExtensionRegistry r = new ExtensionRegistry();
        assertThat(r.getExtensions(Greeter.class)).isEmpty();
    }

    @Test
    void getExtensionsReturnsImmutableSnapshot() {
        ExtensionRegistry r = new ExtensionRegistry();
        r.register("a", Greeter.class, new HelloGreeter());

        List<Greeter> snapshot = r.getExtensions(Greeter.class);
        assertThatThrownBy(() -> snapshot.add(new HiGreeter()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void snapshotIsNotAffectedByLaterRegistrations() {
        ExtensionRegistry r = new ExtensionRegistry();
        r.register("a", Greeter.class, new HelloGreeter());

        List<Greeter> before = r.getExtensions(Greeter.class);
        r.register("a", Greeter.class, new HiGreeter());

        assertThat(before).hasSize(1);
        assertThat(r.getExtensions(Greeter.class)).hasSize(2);
    }

    @Test
    void multiplePluginsContributeToSameExtensionPoint() {
        ExtensionRegistry r = new ExtensionRegistry();
        HelloGreeter hello = new HelloGreeter();
        HiGreeter hi = new HiGreeter();
        r.register("a", Greeter.class, hello);
        r.register("b", Greeter.class, hi);

        assertThat(r.getExtensions(Greeter.class)).containsExactlyInAnyOrder(hello, hi);
    }

    @Test
    void unregisterAllRemovesOnlyThatPluginsEntries() {
        ExtensionRegistry r = new ExtensionRegistry();
        HelloGreeter hello = new HelloGreeter();
        HiGreeter hi = new HiGreeter();
        ByeFarewell bye = new ByeFarewell();
        r.register("a", Greeter.class, hello);
        r.register("a", Farewell.class, bye);
        r.register("b", Greeter.class, hi);

        r.unregisterAll("a");

        assertThat(r.getExtensions(Greeter.class)).containsExactly(hi);
        assertThat(r.getExtensions(Farewell.class)).isEmpty();
    }

    @Test
    void unregisterAllForUnknownPluginIsNoOp() {
        ExtensionRegistry r = new ExtensionRegistry();
        r.register("a", Greeter.class, new HelloGreeter());

        r.unregisterAll("does-not-exist");

        assertThat(r.getExtensions(Greeter.class)).hasSize(1);
    }

    @Test
    void identityRemovalIgnoresEqualsOverrides() {
        // Two distinct instances that .equals() each other; only the registered one should leave.
        class StubbornGreeter implements Greeter {
            @Override public String greet() { return "x"; }
            @Override public boolean equals(Object o) { return o instanceof StubbornGreeter; }
            @Override public int hashCode() { return 1; }
        }
        StubbornGreeter registered = new StubbornGreeter();
        StubbornGreeter twin = new StubbornGreeter();
        assertThat(registered).isEqualTo(twin); // sanity

        ExtensionRegistry r = new ExtensionRegistry();
        r.register("a", Greeter.class, registered);
        r.register("b", Greeter.class, twin);

        r.unregisterAll("a");

        // Only the twin (registered under "b") should remain — by identity.
        List<Greeter> remaining = r.getExtensions(Greeter.class);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0)).isSameAs(twin);
    }

    @Test
    void registerRejectsExtensionThatIsNotInstanceOfExtensionPoint() {
        // Simulate cross-classloader misconfig: instance is an ExtensionPoint but not a Greeter.
        class NotAGreeter implements ExtensionPoint {
        }
        ExtensionRegistry r = new ExtensionRegistry();
        @SuppressWarnings("unchecked")
        Class<ExtensionPoint> pointAsRoot = (Class<ExtensionPoint>) (Class<?>) Greeter.class;

        assertThatThrownBy(() -> r.register("a", pointAsRoot, new NotAGreeter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not assignable");
    }

    @Test
    void registerRejectsNulls() {
        ExtensionRegistry r = new ExtensionRegistry();
        assertThatThrownBy(() -> r.register(null, Greeter.class, new HelloGreeter()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> r.register("a", null, new HelloGreeter()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> r.register("a", Greeter.class, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void concurrentRegisterAndReadDoesNotCrash() throws Exception {
        ExtensionRegistry r = new ExtensionRegistry();
        int n = 200;
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < n; i++) {
                    r.register("a", Greeter.class, new HelloGreeter());
                }
            } catch (Throwable t) {
                error.set(t);
            }
        });
        Thread reader = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < n; i++) {
                    List<Greeter> snap = r.getExtensions(Greeter.class);
                    for (Greeter g : snap) {
                        g.greet();
                    }
                }
            } catch (Throwable t) {
                error.set(t);
            }
        });

        writer.start();
        reader.start();
        start.countDown();
        writer.join(TimeUnit.SECONDS.toMillis(5));
        reader.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(error.get()).isNull();
        assertThat(r.getExtensions(Greeter.class)).hasSize(n);
    }
}
