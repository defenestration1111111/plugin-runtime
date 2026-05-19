package com.defenestration1111111.plugin.core.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestRecorder {

    public static final AtomicInteger startCount = new AtomicInteger();
    public static final AtomicInteger stopCount = new AtomicInteger();
    public static final Map<String, ClassLoader> classLoaders = new ConcurrentHashMap<>();

    private TestRecorder() {
    }

    public static void reset() {
        startCount.set(0);
        stopCount.set(0);
        classLoaders.clear();
    }

    public static void recordStart(String id, ClassLoader cl) {
        startCount.incrementAndGet();
        classLoaders.put(id, cl);
    }

    public static void recordStop() {
        stopCount.incrementAndGet();
    }
}
