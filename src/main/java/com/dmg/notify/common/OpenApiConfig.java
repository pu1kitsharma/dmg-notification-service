package com.dmg.notify.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document at /v3/api-docs and Swagger UI at /swagger-ui.html; "Authorize" takes the Basic credentials. */
@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI notificationServiceApi() {
        return new OpenAPI()
                .info(new Info().title("Multi-tenant Notification Service").version("v1")
                        .description("Platform admin: /tenants, /limits, /platform/reports. Tenant admin: /templates, /channels, "
                                + "/notifications, /reports. Errors are RFC 7807 problem+json."))
                .components(new Components().addSecuritySchemes("basicAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"));
    }
}
