package com.rentalhub.web.graphql;

import com.rentalhub.exception.InvalidRequestException;
import com.rentalhub.web.rest.ApiHeaders;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Hands the acting user to GraphQL, from the same {@code X-Demo-User-Id} header as REST.
 *
 * GraphQL controllers do not see HTTP headers; they see the GraphQL context, a map that travels
 * with one request's execution. This copies the header into it, where mutations read it with
 * {@code @ContextValue}. Queries do not need it: reading listings is open to anyone.
 */
@Component
class DemoUserInterceptor implements WebGraphQlInterceptor {

    static final String USER_ID = "demoUserId";

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        String header = request.getHeaders().getFirst(ApiHeaders.DEMO_USER_ID);
        if (header != null && header.strip().matches("\\d{1,18}")) {
            long userId = Long.parseLong(header.strip());
            request.configureExecutionInput((input, builder) ->
                    builder.graphQLContext(Map.of(USER_ID, userId)).build());
        }
        return chain.next(request);
    }

    /** The acting user, or a translated error saying the header is needed. */
    static long required(Long userId) {
        if (userId == null) {
            throw new InvalidRequestException("error.userRequired");
        }
        return userId;
    }
}
