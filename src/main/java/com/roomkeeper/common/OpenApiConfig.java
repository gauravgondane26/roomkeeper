package com.roomkeeper.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI spec behind Swagger UI (/swagger-ui.html), which is this API's client.
 * Every operation requires a bearer token unless it opts out (the auth endpoints do):
 * log in, then paste the access token into Swagger UI's Authorize dialog.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	static final String BEARER_AUTH = "bearerAuth";

	@Bean
	OpenAPI roomkeeperOpenApi() {
		return new OpenAPI()
			.info(new Info()
				.title("RoomKeeper API")
				.version("v1")
				.description("Hotel booking backend. Errors are RFC 9457 problem details; match on `type`."))
			.components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
				.type(SecurityScheme.Type.HTTP)
				.scheme("bearer")
				.bearerFormat("JWT")
				.description("Access token from POST /api/v1/auth/login")))
			.addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
	}

}
