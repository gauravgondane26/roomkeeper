package com.roomkeeper.auth;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param jwtSecret base64-encoded HMAC key of at least 256 bits; from JWT_SECRET in prod
 * @param issuer the {@code iss} claim written into and required on every access token
 * @param accessTokenTtl how long an access token stays valid
 */
@Validated
@ConfigurationProperties("roomkeeper.auth")
public record AuthProperties(@NotBlank String jwtSecret, @NotBlank String issuer, @NotNull Duration accessTokenTtl) {
}
