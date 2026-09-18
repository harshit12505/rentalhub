package com.rentalhub.config;

import com.rentalhub.web.rest.ApiHeaders;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The API's description, served at {@code /v3/api-docs} and shown by Swagger UI at
 * {@code /swagger-ui.html}.
 *
 * springdoc builds most of it by reading the controllers: every path, parameter and response
 * type. The controllers add what cannot be read from the code — a summary, a description, and
 * example payloads — with {@code @Operation}. This class adds what applies to all of them.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private static final String DESCRIPTION = """
            The REST API of RentalHub, an Airbnb-style listing and booking platform (a portfolio project).

            **Who is acting.** There is no login. Endpoints that act as someone read the user id from the
            `X-Demo-User-Id` header. Demo users are created by hand until the seeder of phase 9.

            **Language.** Every message comes back in English, Hindi or Spanish: `?lang=hi` on any request
            (remembered in a cookie), otherwise `Accept-Language`, otherwise English.

            **Errors** are RFC 9457 problem details (`application/problem+json`): a translated `title` and
            `detail`, and a `messageKey` a client can act on without reading the text.

            **Money** is always in the listing's own currency. Add `?currency=USD` (INR, EUR, GBP, AED) to a
            read to also see a converted figure, which is for display only.

            **GraphQL.** The same listings, bookings and reviews are also available at `POST /graphql`
            (try it in the browser at `/graphiql`).
            """;

    @Bean
    OpenAPI rentalHubApi() {
        return new OpenAPI().info(new Info()
                .title("RentalHub API")
                .version("phase 7")
                .description(DESCRIPTION));
    }

    /** The acting-user header, described once, for every endpoint that reads it. */
    @Bean
    OperationCustomizer demoUserHeader() {
        return (operation, handlerMethod) -> {
            if (operation.getParameters() != null) {
                operation.getParameters().stream()
                        .filter(parameter -> ApiHeaders.DEMO_USER_ID.equals(parameter.getName()))
                        .forEach(parameter -> parameter
                                .description("The id of the user acting. Stands in for a login; see the API description.")
                                .example(2));
            }
            return operation;
        };
    }
}
