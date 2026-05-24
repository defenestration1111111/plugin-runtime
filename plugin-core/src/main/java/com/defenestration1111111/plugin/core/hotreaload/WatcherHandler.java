package com.defenestration1111111.plugin.core.hotreaload;

import java.nio.file.Path;

public interface WatcherHandler {

    void onSettled(Path jar);

    void onDeleted(Path jar);
}
