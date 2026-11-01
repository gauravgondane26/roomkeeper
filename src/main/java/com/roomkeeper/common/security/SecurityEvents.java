package com.roomkeeper.common.security;

import com.roomkeeper.common.LogSafe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records what an attack looks like: a failed login, a webhook delivery that isn't Stripe's,
 * a request for something the caller may not have. Each is a WARN line, with no password,
 * token or unknown email in it, and a count in {@code roomkeeper_security_events_total} by
 * {@code event}, so a dashboard can alert on a spike (a password spray, someone probing ids).
 */
@Component
public class SecurityEvents {

	private static final Logger log = LoggerFactory.getLogger(SecurityEvents.class);

	private final Counter loginFailures;

	private final Counter webhookRejections;

	private final Counter accessDenials;

	public SecurityEvents(MeterRegistry registry) {
		// Registered up front, so each starts at 0 rather than appearing with its first event
		this.loginFailures = counter(registry, "login_failed");
		this.webhookRejections = counter(registry, "webhook_rejected");
		this.accessDenials = counter(registry, "access_denied");
	}

	/** A wrong password for the user, or, with no user ({@code null}), for an email with no account. */
	public void loginFailed(Long userId) {
		loginFailures.increment();
		if (userId != null) {
			log.warn("Failed login for user {}", userId);
		}
		else {
			log.warn("Failed login for an email with no account");
		}
	}

	/** A payment webhook delivery refused before it was applied: a bad or missing signature. */
	public void webhookRejected(String reason) {
		webhookRejections.increment();
		log.warn("Refused a payment webhook delivery: {}", reason);
	}

	/** A signed-in user was refused (403) what they asked for. */
	public void accessDenied(String user, String method, String path) {
		accessDenials.increment();
		log.warn("Access denied to user {}: {} {}", LogSafe.of(user), method, LogSafe.of(path));
	}

	private static Counter counter(MeterRegistry registry, String event) {
		return Counter.builder("roomkeeper.security.events")
			.description("Failed logins, refused webhook deliveries and denied requests, by event")
			.tag("event", event)
			.register(registry);
	}

}
