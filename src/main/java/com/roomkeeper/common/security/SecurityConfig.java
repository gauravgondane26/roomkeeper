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

/**
 * Stateless API security: no sessions, no cookies, no CSRF (clients send bearer tokens,
 * not cookies), no login page. Everything requires authentication unless listed here.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, ProblemDetailsSecurityHandler problemHandler,
			JwtAuthenticationConverter jwtAuthenticationConverter) {
		return http
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
				.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
				// API docs: Swagger UI is the client
				.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
				// Errors thrown on permitted routes are forwarded here and must stay visible
				.requestMatchers("/error").permitAll()
				.requestMatchers("/api/v1/admin/**").hasRole("HOTEL_MANAGER")
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

	/** Hashes are stored with an algorithm prefix ({bcrypt}...), so the algorithm can change later. */
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

}
