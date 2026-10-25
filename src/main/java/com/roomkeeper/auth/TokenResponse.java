package com.roomkeeper.auth;

/** @param expiresIn seconds until the access token expires */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

	static TokenResponse from(AccessToken token) {
		return new TokenResponse(token.value(), "Bearer", token.expiresIn().toSeconds());
	}

}
