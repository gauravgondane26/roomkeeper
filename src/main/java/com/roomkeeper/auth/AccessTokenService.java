package com.roomkeeper.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.roomkeeper.user.Role;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues short-lived access tokens: subject is the user id, roles claim holds the role. */
@Service
public class AccessTokenService {

	private final JwtEncoder encoder;

	private final AuthProperties properties;

	private final Clock clock;

	public AccessTokenService(JwtEncoder encoder, AuthProperties properties, Clock clock) {
		this.encoder = encoder;
		this.properties = properties;
		this.clock = clock;
	}

	public AccessToken issue(long userId, Role role) {
		Instant now = clock.instant();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(properties.issuer())
			.subject(String.valueOf(userId))
			.issuedAt(now)
			.expiresAt(now.plus(properties.accessTokenTtl()))
			.claim(JwtConfig.ROLES_CLAIM, List.of(role.name()))
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new AccessToken(value, properties.accessTokenTtl());
	}

}
