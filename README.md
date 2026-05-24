# plugin-runtime

A Java library for loading, isolating, and hot-reloading plugins at runtime.
Each plugin runs in its own `ClassLoader`, so plugins can bundle their own dependencies
without conflicting with the host application or with each other.

---

## Modules

| Module | Purpose |
|---|---|
| `plugin-api` | Contracts compiled against by both the host app and plugin authors |
| `plugin-core` | Runtime: `PluginManager`, classloader isolation, lifecycle FSM, hot reload |
| `plugin-spring-boot-starter` | Spring Boot auto-configuration |

---

## Requirements

- Java 21+
- Maven 3.9+
- Spring Boot 3.5+ (starter only)

---

## Quick start — Spring Boot

Add the starter (it pulls in `plugin-core` and `plugin-api` transitively):

```xml
<dependency>
    <groupId>com.defenestration1111111</groupId>
    <artifactId>plugin-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Configure in `application.yml`:

```yaml
plugins:
  directory: /var/app/plugins
  watch: true
```

That's it. On startup the directory is scanned, plugins are started, and the watcher
keeps watching — new jars are picked up automatically, replaced jars are reloaded,
deleted jars are uninstalled. No code required.

`PluginManager` is available as a regular Spring bean for reading extensions:

```java
@Service
class GreetService {

    private final PluginManager plugins;

    GreetService(PluginManager plugins) {
        this.plugins = plugins;
    }

    public List<String> greetAll(String name) {
        return plugins.getExtensions(Greeter.class).stream()
                .map(g -> g.greet(name))
                .toList();
    }
}
```

### All `plugins.*` properties

| Property | Default | Description |
|---|---|---|
| `plugins.enabled` | `true` | Set to `false` to disable entirely — no beans are created. |
| `plugins.directory` | — | Directory to scan on startup. If absent, manager starts empty. |
| `plugins.watch` | `false` | Keep watching the directory after initial scan. |
| `plugins.auto-start` | `true` | Start plugins immediately after install. |
| `plugins.exported-packages` | `[]` | Extra host packages visible to plugins (see Extension points). |

---

## Quick start — without Spring

```java
PluginManager manager = new PluginManager(getClass().getClassLoader());

// initial scan
DiscoveryReport report = new PluginDiscovery().scan(Path.of("/var/app/plugins"));
manager.install(report);
manager.startAll();

// keep watching for changes
manager.attachWatcher(Path.of("/var/app/plugins"));

// on shutdown
manager.close();  // stops all plugins, closes classloaders
```

New, replaced, and deleted jars are handled automatically by the watcher after the initial scan.

---

## Writing a plugin

### 1. Declare the dependency as `provided`

```xml
<dependency>
    <groupId>com.defenestration1111111</groupId>
    <artifactId>plugin-api</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

`provided` is required — the host exports `plugin-api` at runtime.
Bundling it in the plugin jar causes classloader mismatches.

### 2. Implement `Plugin`

```java
package com.example.myplugin;

import com.defenestration1111111.plugin.api.Plugin;
import com.defenestration1111111.plugin.api.PluginContext;

public class HelloPlugin implements Plugin {

    @Override
    public void start(PluginContext ctx) {
        // register extensions, start background threads, etc.
    }

    @Override
    public void stop() {
        // release resources
    }
}
```

### 3. Add a descriptor

`src/main/resources/META-INF/plugin.properties`:

```properties
id=hello
version=1.0.0
mainClass=com.example.myplugin.HelloPlugin
```

Exactly three keys. Any unknown key throws `PluginDescriptorException` on load —
this is intentional to catch typos like `mainclass` instead of `mainClass`.

---

## Extension points

Extension points let plugins contribute implementations of interfaces declared by the host.

### Declare in the host

```java
package com.example.host;

import com.defenestration1111111.plugin.api.ExtensionPoint;

public interface Greeter extends ExtensionPoint {
    String greet(String name);
}
```

The package must be visible to plugins. In Spring Boot, add it to `exported-packages`:

```yaml
plugins:
  exported-packages:
    - com.example.host
```

Without Spring:

```java
PluginManager manager = new PluginManager(
        getClass().getClassLoader(),
        Set.of("com.defenestration1111111.plugin.api", "com.example.host"));
```

### Register from the plugin

```java
@Override
public void start(PluginContext ctx) {
    ctx.register(Greeter.class, new HelloGreeter());
}
```

### Consume in the host

```java
List<Greeter> greeters = manager.getExtensions(Greeter.class);
```

Returns an immutable snapshot. Do not cache it across reloads —
call it fresh each time you need the current list.

When a plugin stops or is unloaded, all its extensions are removed automatically.

---

## How it works — lifecycle

Every plugin moves through these phases:

```
DISCOVERED → RESOLVED → LOADED → STARTED
                                     ↓
                                  STOPPED → UNLOADED
                                     ↓
                                  FAILED  (terminal — recover via reload)
```

Normally you never touch this directly — the watcher and the Spring lifecycle
drive all the transitions. The current phase is readable if you need it:

```java
Phase phase = manager.phase("hello");
Optional<Throwable> cause = manager.failure("hello");  // non-empty only in FAILED
```

`FAILED` is terminal. The only way back is `manager.reload("hello", fixedJar)`.

---

## Hot reload in detail

The watcher handles everything automatically, but reload can also be triggered manually:

```java
manager.reload("hello", Path.of("/var/app/plugins/hello-2.0.0.jar"));
```

What happens atomically under a per-plugin lock:

1. The new jar's descriptor is read first — if it's invalid, the live plugin is untouched.
2. Old plugin: `stop` → `unload` (classloader closed, no leak).
3. New plugin: `resolve` → `load` → `start`.

> **Note:** `getExtensions` returns a snapshot. References obtained before a reload
> point to old instances from the unloaded classloader. Always call `getExtensions` fresh.

> **macOS:** `WatchService` polls every ~10 seconds on macOS. On Linux events are near-instant.

---

## Manual lifecycle control

For cases where you need full control — scripted rollouts, conditional startup, etc.:

```java
PluginManager manager = new PluginManager(getClass().getClassLoader());

DiscoveryReport report = new PluginDiscovery().scan(pluginsDir);
manager.install(report);   // all plugins → DISCOVERED

manager.startAll();        // DISCOVERED → RESOLVED → LOADED → STARTED

// check for failures after batch operation
for (String id : manager.ids()) {
    if (manager.phase(id) == Phase.FAILED) {
        manager.failure(id).ifPresent(e -> log.error("Plugin {} failed", id, e));
    }
}

manager.stop("hello");     // STARTED → STOPPED
manager.unload("hello");   // STOPPED → UNLOADED
manager.close();           // stops and unloads everything
```

---

## Common pitfalls

- **Extension point package not exported** → `IllegalArgumentException` on `ctx.register`.
  Add the package to `exported-packages` / the `PluginManager` constructor.
- **Plugin bundles `plugin-api`** → classloader mismatch at cast time.
  Use `<scope>provided</scope>` in the plugin's pom.
- **Caching `getExtensions` result** → stale references after reload.
  Call `getExtensions` fresh on each use.
- **Threads created with `new Thread()`** inside a plugin → classloader leak on unload.
  Use `ctx.threadFactory()` instead.
