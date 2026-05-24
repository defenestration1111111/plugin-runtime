package com.defenestration1111111.plugin.core.hotreload;

import java.nio.file.Path;

public interface WatcherHandler {

    void onSettled(Path jar);

    void onDeleted(Path jar);
}
