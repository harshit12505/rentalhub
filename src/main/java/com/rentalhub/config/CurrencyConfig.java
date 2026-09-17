package com.rentalhub.config;

import com.rentalhub.fx.ExchangeRateApiSource;
import com.rentalhub.fx.ExchangeRateSource;
import com.rentalhub.fx.FxSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Where exchange rates come from (see CurrencyService for how they are cached).
 *
 * The HTTP client gets short timeouts of its own. A rates fetch happens while someone's
 * request waits for it, so a provider that hangs must cost that request a few seconds, not
 * the default of waiting indefinitely.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FxSettings.class)
public class CurrencyConfig {

    @Bean
    public ExchangeRateSource exchangeRateSource(FxSettings settings) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(client);
        requests.setReadTimeout(settings.readTimeout());
        return new ExchangeRateApiSource(RestClient.builder().requestFactory(requests).build(), settings.ratesUrl());
    }
}
