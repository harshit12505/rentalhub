package com.rentalhub.config;

import com.rentalhub.ai.AiSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The AI feature's own settings.
 *
 * The models themselves are configured by Spring AI from {@code spring.ai.*}, and switched
 * off entirely when there is no key (ai/AiEnvironmentPostProcessor), so there is nothing to
 * wire here beyond this application's own tuning.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiSettings.class)
public class AiConfig {
}
