package com.defenestration1111111.plugin.spring;

import com.defenestration1111111.plugin.core.classloader.PluginClassLoader;
import com.defenestration1111111.plugin.core.lifecycle.PluginManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashSet;
import java.util.Set;

@AutoConfiguration
@EnableConfigurationProperties(PluginSystemProperties.class)
@ConditionalOnProperty(prefix = "plugins", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PluginSystemAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public PluginManager pluginManager(PluginSystemProperties props) {
        Set<String> exported = new LinkedHashSet<>(PluginClassLoader.BASELINE_EXPORTED_PACKAGES);
        if (props.getExportedPackages() != null) {
            exported.addAll(props.getExportedPackages());
        }
        ClassLoader host = PluginSystemAutoConfiguration.class.getClassLoader();
        PluginManager manager = new PluginManager(host, exported);
        manager.setAutoStartOnDiscovery(props.isAutoStart());
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    public PluginSystemLifecycle pluginSystemLifecycle(PluginManager manager,
                                                       PluginSystemProperties props) {
        return new PluginSystemLifecycle(manager, props);
    }
}
