package com.defenestration1111111.plugin.api;

public interface Plugin {

    default void start(PluginContext context) throws Exception {
    }

    default void stop() throws Exception {
    }
}
