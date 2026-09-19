package com.rentalhub.web;

import com.rentalhub.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The health check a hosting platform polls. With Postgres and Redis both up it is UP, and
 * Redis is reported by the app's own indicator, not Spring Boot's (whose DOWN would fail the
 * whole check for a cache the app can live without; see SharedCacheHealthIndicator).
 */
class HealthApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private HealthEndpoint health;

    @Test
    @DisplayName("/actuator/health is 200 UP, and shows no details to the public")
    void up() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("Redis is checked by the shared-cache indicator; Spring Boot's own Redis check is off")
    void redisCheckedByTheAppsOwnIndicator() {
        assertThat(health.healthForPath("sharedCache")).isNotNull();
        assertThat(health.healthForPath("redis")).isNull();
        assertThat(health.healthForPath("db")).isNotNull();
    }
}
