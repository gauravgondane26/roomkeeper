package com.roomkeeper.outbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stands in for an email provider: logs each email instead of sending it, and ignores an
 * idempotency key it has already sent, as a provider would. It remembers the last
 * {@value #REMEMBERED} emails, which tests read back.
 */
@Component
public class FakeEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger(FakeEmailSender.class);

	static final int REMEMBERED = 10_000;

	/** An email, and how many times it was handed over, duplicates included. */
	private record Sent(Email email, int deliveries) {
	}

	// Oldest first; the oldest is forgotten once there are too many
	private final Map<String, Sent> sent = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Sent> eldest) {
			return size() > REMEMBERED;
		}
	};

	@Override
	public synchronized void send(Email email) {
		Sent before = sent.get(email.idempotencyKey());
		if (before != null) {
			sent.put(email.idempotencyKey(), new Sent(before.email(), before.deliveries() + 1));
			log.info("Email {} was already sent; ignoring the duplicate", email.idempotencyKey());
			return;
		}
		sent.put(email.idempotencyKey(), new Sent(email, 1));
		log.info("Email to {} ({}): {}", email.to(), email.idempotencyKey(), email.subject());
	}

	/** The emails sent to the address, oldest first. */
	public synchronized List<Email> sentTo(String address) {
		return sent.values().stream().map(Sent::email).filter(email -> email.to().equals(address)).toList();
	}

	/** How many times the email with this key was handed over, duplicates included; 0 if never. */
	public synchronized int deliveriesOf(String idempotencyKey) {
		Sent email = sent.get(idempotencyKey);
		return email == null ? 0 : email.deliveries();
	}

}
