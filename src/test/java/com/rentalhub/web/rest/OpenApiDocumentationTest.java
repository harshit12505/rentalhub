package com.rentalhub.web.rest;

import com.rentalhub.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The spec asks for "@Operation descriptions and example payloads on every endpoint". This
 * reads the generated OpenAPI description and holds every endpoint to that, so an endpoint
 * added later without its documentation fails the build rather than quietly going undocumented.
 */
class OpenApiDocumentationTest extends IntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("every REST endpoint is in the OpenAPI description")
    void everyEndpointIsDocumented() throws Exception {
        Set<String> documented = new TreeSet<>();
        operations().forEach(operation -> documented.add(operation.method() + " " + operation.path()));

        Set<String> implemented = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(mapping -> mapping.getPatternValues().stream()
                .filter(path -> path.startsWith("/api/") || path.startsWith("/images/"))
                .forEach(path -> mapping.getMethodsCondition().getMethods().forEach(method ->
                        implemented.add(method.name() + " " + path.replaceAll("\\{(\\w+):[^/]+}", "{$1}")))));

        assertThat(documented).isEqualTo(implemented).hasSizeGreaterThanOrEqualTo(23);
    }

    @Test
    @DisplayName("every endpoint has a summary, a description, a tag, and at least one example payload")
    void everyEndpointIsExplained() throws Exception {
        for (Operation operation : operations()) {
            JsonNode node = operation.node();
            String name = operation.method() + " " + operation.path();
            assertThat(node.path("summary").asString("")).as("summary of %s", name).isNotBlank();
            assertThat(node.path("description").asString("")).as("description of %s", name).isNotBlank();
            assertThat(node.path("tags").isEmpty()).as("tag of %s", name).isFalse();
            // springdoc writes one example as "example" and several as "examples"; both are OpenAPI.
            assertThat(node.toString()).as("an example somewhere in %s", name).contains("\"example");

            JsonNode jsonBody = node.path("requestBody").path("content").path("application/json");
            if (!jsonBody.isMissingNode()) {
                assertThat(jsonBody.has("example") || !jsonBody.path("examples").isEmpty())
                        .as("the JSON request body of %s has an example", name)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("Swagger UI is at /swagger-ui.html")
    void swaggerUi() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
    }

    private List<Operation> operations() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Operation> operations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : json.readTree(body).path("paths").properties()) {
            for (Map.Entry<String, JsonNode> method : path.getValue().properties()) {
                operations.add(new Operation(method.getKey().toUpperCase(Locale.ROOT), path.getKey(),
                        method.getValue()));
            }
        }
        return operations;
    }

    private record Operation(String method, String path, JsonNode node) {
    }
}
