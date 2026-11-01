package com.roomkeeper.common;

import java.net.URI;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * In prod, refuses to start without a Redis that asks for a password. Redis holds
 * idempotency records and rate-limit buckets, which anyone who could reach it
 * unauthenticated could delete; and since everything that uses Redis carries on without it,
 * a Redis that isn't configured at all would only show as limits quietly not applying.
 * <p>
 * Either REDIS_HOST with REDIS_PASSWORD (Compose), or one URL carrying the password in
 * SPRING_DATA_REDIS_URL, which takes precedence over the host (Render's Key Value hands
 * over a URL, with a password once internal authentication is on).
 */
@Component
@Profile("prod")
class ProductionRedisCheck implements InitializingBean {

	private final String url;

	private final String host;

	private final String password;

	ProductionRedisCheck(Environment environment) {
		this.url = environment.getProperty("spring.data.redis.url");
		this.host = environment.getProperty("spring.data.redis.host");
		this.password = environment.getProperty("spring.data.redis.password");
	}

	@Override
	public void afterPropertiesSet() {
		if (StringUtils.hasText(url)) {
			if (!StringUtils.hasText(passwordIn(url))) {
				throw new IllegalStateException("The Redis URL (SPRING_DATA_REDIS_URL) carries no password; turn on the"
						+ " Redis server's authentication and use the URL that includes it");
			}
			return;
		}
		if (!StringUtils.hasText(host)) {
			throw new IllegalStateException("No Redis is configured: set REDIS_HOST and REDIS_PASSWORD, or"
					+ " SPRING_DATA_REDIS_URL");
		}
		if (!StringUtils.hasText(password)) {
			throw new IllegalStateException("Redis at REDIS_HOST has no password (REDIS_PASSWORD); prod's Redis must ask"
					+ " for one");
		}
	}

	/** The password in a redis:// URL's user info ("user:password", or a lone "password"), or null. */
	private static String passwordIn(String redisUrl) {
		String userInfo = URI.create(redisUrl).getRawUserInfo();
		if (userInfo == null) {
			return null;
		}
		int colon = userInfo.indexOf(':');
		return colon < 0 ? userInfo : userInfo.substring(colon + 1);
	}

}
