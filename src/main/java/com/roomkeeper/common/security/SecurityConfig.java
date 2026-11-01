package com.roomkeeper.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Stateless API security: no sessions, no cookies, no CSRF (clients send bearer tokens,
 * not cookies), no login page. Everything requires authentication unless listed here.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	/** The API answers JSON: a browser may load nothing for it and frame it nowhere. */
	private static final String API_POLICY = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

	/** Stripe's return pages style themselves inline and run no scripts. */
	private static final String PAGES_POLICY = "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; "
			+ "base-uri 'none'; form-action 'none'";

	/** Swagger UI loads its own scripts and styles and calls the API from the same origin. */
	private static final String SWAGGER_POLICY = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
			+ "frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, ProblemDetailsSecurityHandler problemHandler,
			JwtAuthenticationConverter jwtAuthenticationConverter) {
		RequestMatcher swaggerUi = new OrRequestMatcher(path("/swagger-ui/**"), path("/swagger-ui.html"));
		RequestMatcher pages = path("/payments/**");
		return http
			// Spring Security's defaults (nosniff, frame DENY, no-store) plus what a browser may load,
			// and no referrer, so URLs with ids or Stripe session ids aren't passed on
			.headers(headers -> headers
				.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(swaggerUi, policy(SWAGGER_POLICY)))
				.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(pages, policy(PAGES_POLICY)))
				.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
						new NegatedRequestMatcher(new OrRequestMatcher(swaggerUi, pages)), policy(API_POLICY)))
				.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
				.addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
						"camera=(), microphone=(), geolocation=(), payment=()")))
			.csrf(csrf -> csrf.disable())
			.httpBasic(basic -> basic.disable())
			.formLogin(form -> form.disable())
			.logout(logout -> logout.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				// Refresh and logout prove identity with the refresh token itself, so they work
				// after the access token has expired
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh",
						"/api/v1/auth/logout").permitAll()
				// Guests browse hotels without logging in
				.requestMatchers(HttpMethod.GET, "/api/v1/hotels/**").permitAll()
				// Stripe sends the guest back to these static pages after checkout
				.requestMatchers(HttpMethod.GET, "/payments/*.html").permitAll()
				// Stripe's webhook proves itself with a signature, checked by the endpoint
				.requestMatchers(HttpMethod.POST, "/webhook/payment").permitAll()
				// Health probes for load balancers and container health checks
				.requestMatchers(HttpMethod.GET, "/livez", "/readyz").permitAll()
				// Actuator only answers on the management port, which isn't published; on the
				// public port these paths are 404
				.requestMatchers("/actuator", "/actuator/**").permitAll()
				// API docs: Swagger UI is the client, and / sends browsers to it. In prod they're off
				// unless API_DOCS_ENABLED is set, and then these paths are 404
				.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
				.requestMatchers(HttpMethod.GET, "/").permitAll()
				// Errors thrown on permitted routes are forwarded here and must stay visible
				.requestMatchers("/error").permitAll()
				.requestMatchers("/api/v1/admin/**").hasRole("HOTEL_MANAGER")
				.requestMatchers("/api/v1/moderation/**").hasRole("ADMIN")
				.anyRequest().authenticated())
			// Bearer tokens are validated by Spring's resource server support (signature,
			// expiry, issuer: see JwtConfig); missing or bad tokens get our 401 problem
			.oauth2ResourceServer(resourceServer -> resourceServer
				.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
				.authenticationEntryPoint(problemHandler)
				.accessDeniedHandler(problemHandler))
			.exceptionHandling(errors -> errors
				.authenticationEntryPoint(problemHandler)
				.accessDeniedHandler(problemHandler))
			.build();
	}

	private static RequestMatcher path(String pattern) {
		return PathPatternRequestMatcher.withDefaults().matcher(pattern);
	}

	private static HeaderWriter policy(String contentSecurityPolicy) {
		return new StaticHeadersWriter("Content-Security-Policy", contentSecurityPolicy);
	}

	/** Hashes are stored with an algorithm prefix ({bcrypt}...), so the algorithm can change later. */
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

}
