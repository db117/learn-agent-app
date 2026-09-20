package com.db117.learnagent.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "learn-agent")
public interface RuntimeConfig {
    @WithName("data-dir")
    String dataDir();

    @WithName("memory-enabled")
    boolean memoryEnabled();
}
