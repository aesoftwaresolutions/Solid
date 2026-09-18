package com.aesoftwaresolutions.solid.platform;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the API for the generated OpenAPI document. The paths and schemas come from the controllers, so this
 * only adds what the code cannot say for itself: how to authenticate, and that money is a string.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    private static final String DESCRIPTION = """
            The API behind Solid. One installation holds one household's or business's books, so every endpoint \
            needs a signed-in session.

            Authenticating: POST /api/v1/auth/login, then complete MFA with POST /api/v1/auth/mfa/verify. The login \
            response carries an opaque bearer token for scripts (send it as `Authorization: Bearer ...`); browsers \
            get an HttpOnly `solid_session` cookie instead and must also send the `X-XSRF-TOKEN` header on writes.

            Money is always an object like `{"amount": "12.34", "currency": "USD"}`. The amount is a **string** on \
            purpose: parsing it as a float loses cents. Dates are ISO-8601.""";

    @Bean
    OpenAPI solidOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        Schema<?> money = new Schema<>()
                .type("object")
                .description("An exact amount. `amount` is a decimal string, never a number.")
                .addProperty("amount", new StringSchema().example("12.34"))
                .addProperty("currency", new StringSchema().example("USD"));

        return new OpenAPI()
                .info(new Info()
                        .title("Solid")
                        .version(build != null ? build.getVersion() : "dev")
                        .description(DESCRIPTION)
                        .license(new License().name("See the repository")))
                .components(new Components()
                        .addSchemas("Money", money)
                        .addSecuritySchemes("bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("The opaque session token from POST /api/v1/auth/login."))
                        .addSecuritySchemes("session", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("solid_session")
                                .description("Browser session cookie; writes also need the X-XSRF-TOKEN header.")))
                .addSecurityItem(new SecurityRequirement().addList("bearer").addList("session"));
    }
}
