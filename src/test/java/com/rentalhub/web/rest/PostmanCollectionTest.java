package com.rentalhub.web.rest;

import com.rentalhub.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Postman collection in postman/ covers every REST endpoint, and the environment file
 * defines what the collection's requests use.
 *
 * The collection is a file a person maintains by hand, which is exactly the kind of thing that
 * falls behind; this compares it with the endpoints Spring actually serves.
 */
class PostmanCollectionTest extends IntegrationTest {

    private static final Path COLLECTION = Path.of("postman", "RentalHub.postman_collection.json");
    private static final Path ENVIRONMENT = Path.of("postman", "RentalHub.local.postman_environment.json");

    @Autowired
    private JsonMapper json;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("every REST endpoint has a request in the collection, and GraphQL is there too")
    void coversEveryEndpoint() throws Exception {
        List<JsonNode> requests = requests(json.readTree(Files.readString(COLLECTION)).path("item"));
        Set<String> covered = new TreeSet<>();
        for (JsonNode request : requests) {
            String path = request.path("url").path("raw").asString()
                    .replace("{{baseUrl}}", "")
                    .replaceAll("\\?.*$", "")
                    .replaceAll("\\{\\{\\w+}}|\\d+", "{}");
            covered.add(request.path("method").asString() + " " + path);
        }

        Set<String> endpoints = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(mapping -> mapping.getPatternValues().stream()
                .filter(path -> path.startsWith("/api/") || path.startsWith("/images/"))
                .forEach(path -> mapping.getMethodsCondition().getMethods().forEach(method ->
                        endpoints.add(method.name() + " " + path.replaceAll("\\{[^/]+}", "{}")))));

        assertThat(covered).containsAll(endpoints).contains("POST /graphql");
    }

    @Test
    @DisplayName("the environment defines the base URL and the demo users the requests act as")
    void environmentDefinesTheVariables() throws Exception {
        JsonNode environment = json.readTree(Files.readString(ENVIRONMENT));
        Set<String> keys = new TreeSet<>();
        environment.path("values").forEach(value -> keys.add(value.path("key").asString()));

        assertThat(keys).containsExactlyInAnyOrder("baseUrl", "hostId", "guestId");
        assertThat(Files.readString(COLLECTION)).contains("{{baseUrl}}", "{{hostId}}", "{{guestId}}");
    }

    private static List<JsonNode> requests(JsonNode items) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode item : items) {
            if (item.has("request")) {
                found.add(item.path("request"));
            } else {
                found.addAll(requests(item.path("item")));
            }
        }
        return found;
    }
}
