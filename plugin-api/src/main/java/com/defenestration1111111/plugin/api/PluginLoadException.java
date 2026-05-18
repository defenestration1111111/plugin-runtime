package com.defenestration1111111.plugin.api;

public class PluginLoadException extends PluginException {

    public PluginLoadException(String message) {
        super(message);
    }

    public PluginLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
