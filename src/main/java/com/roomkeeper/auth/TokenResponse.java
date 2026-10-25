package com.roomkeeper.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @param expiresIn seconds until the access token expires
 * @param refreshToken absent when it couldn't be stored (Redis down): log in again later
 * @param refreshExpiresIn seconds until an unused refresh token expires
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken,
		Long refreshExpiresIn) {

	static TokenResponse of(AccessToken accessToken, String refreshToken, AuthProperties properties) {
		Long refreshExpiresIn = refreshToken != null ? properties.refreshTokenTtl().toSeconds() : null;
		return new TokenResponse(accessToken.value(), "Bearer", accessToken.expiresIn().toSeconds(), refreshToken,
				refreshExpiresIn);
	}

}
