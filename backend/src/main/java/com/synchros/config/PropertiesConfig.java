package com.synchros.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SynchrosProperties.class)
public class PropertiesConfig {
}
