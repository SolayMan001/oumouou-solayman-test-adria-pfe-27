package com.adria.bank.gateway;

import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutes {
    @Bean
    public RouteLocator bankingRoutes(RouteLocatorBuilder routes) {
        return routes.routes()
                .route("account-rest", route -> route
                        .path("/api/accounts", "/api/accounts/**", "/api/transfers", "/api/transfers/**")
                        .uri("lb://account-service"))
                .route("transaction-rest", route -> route
                        .path("/api/transactions", "/api/transactions/**")
                        .uri("lb://transaction-service"))
                .route("account-graphql", route -> route.path("/account/graphql")
                        .filters(filter -> filter.setPath("/graphql"))
                        .uri("lb://account-service"))
                .route("transaction-graphql", route -> route.path("/transaction/graphql")
                        .filters(filter -> filter.setPath("/graphql"))
                        .uri("lb://transaction-service"))
                .route("account-mcp", route -> route.path("/account/mcp", "/account/mcp/**")
                        .filters(filter -> filter.stripPrefix(1))
                        .uri("lb://account-service"))
                .route("transaction-mcp", route -> route.path("/transaction/mcp", "/transaction/mcp/**")
                        .filters(filter -> filter.stripPrefix(1))
                        .uri("lb://transaction-service"))
                .build();
    }

    @Bean
    public HttpClientCustomizer noAutomaticTransportRetries() {
        // Reactor Netty otherwise retries some connection resets once. Never replay a financial write.
        return client -> client.disableRetry(true);
    }
}
