package com.rentalhub.config;

import com.rentalhub.web.graphql.GraphQlScalars;
import org.springframework.boot.graphql.autoconfigure.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

/**
 * GraphQL wiring beyond what Spring Boot does by itself: the schema's custom scalars.
 *
 * Everything else is conventional: the schema is read from {@code resources/graphql/}, and the
 * {@code @QueryMapping}, {@code @MutationMapping}, {@code @SchemaMapping} and
 * {@code @BatchMapping} methods in web/graphql are found by Spring for GraphQL.
 */
@Configuration(proxyBeanMethods = false)
public class GraphQlConfig {

    @Bean
    RuntimeWiringConfigurer graphQlScalars() {
        return wiring -> wiring
                .scalar(GraphQlScalars.DECIMAL)
                .scalar(GraphQlScalars.DATE)
                .scalar(GraphQlScalars.DATE_TIME);
    }

    /**
     * Fails the start-up if a schema field has nothing to fetch it, instead of that field
     * quietly answering null at run time. Spring for GraphQL can list such fields; this makes
     * the list a hard error.
     */
    @Bean
    GraphQlSourceBuilderCustomizer schemaMappingCheck() {
        return builder -> builder.inspectSchemaMappings(report -> {
            if (!report.unmappedFields().isEmpty()) {
                throw new IllegalStateException("GraphQL fields with no data fetcher: " + report.unmappedFields());
            }
        });
    }
}
