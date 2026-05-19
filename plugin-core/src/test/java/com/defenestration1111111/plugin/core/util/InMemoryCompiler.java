package com.defenestration1111111.plugin.core.util;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class InMemoryCompiler {

    private InMemoryCompiler() {
    }

    public static Map<String, byte[]> compile(String fqn, String source) {
        return compile(Map.of(fqn, source));
    }

    public static Map<String, byte[]> compile(Map<String, String> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No Java compiler available — run tests with a JDK, not a JRE");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standardFm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        InMemoryFileManager fm = new InMemoryFileManager(standardFm);

        List<JavaFileObject> sourceFiles = sources.entrySet().stream()
                .map(e -> (JavaFileObject) new InMemoryJavaSource(e.getKey(), e.getValue()))
                .toList();

        List<String> options = List.of("-classpath", System.getProperty("java.class.path"));

        JavaCompiler.CompilationTask task = compiler.getTask(null, fm, diagnostics, options, null, sourceFiles);
        if (!task.call()) {
            String errors = diagnostics.getDiagnostics().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining("\n"));
            throw new IllegalStateException("Compilation failed:\n" + errors);
        }

        return fm.outputs();
    }

    private static final class InMemoryJavaSource extends SimpleJavaFileObject {
        private final String source;

        InMemoryJavaSource(String fqn, String source) {
            super(URI.create("string:///" + fqn.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class InMemoryClassFile extends SimpleJavaFileObject {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        InMemoryClassFile(String fqn) {
            super(URI.create("mem:///" + fqn.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
        }

        @Override
        public OutputStream openOutputStream() {
            return buf;
        }

        byte[] bytes() {
            return buf.toByteArray();
        }
    }

    private static final class InMemoryFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        private final Map<String, InMemoryClassFile> compiled = new HashMap<>();

        InMemoryFileManager(JavaFileManager delegate) {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind, FileObject sibling) {
            InMemoryClassFile file = new InMemoryClassFile(className);
            compiled.put(className, file);
            return file;
        }

        Map<String, byte[]> outputs() {
            Map<String, byte[]> result = new HashMap<>();
            compiled.forEach((k, v) -> result.put(k, v.bytes()));
            return result;
        }
    }
}
