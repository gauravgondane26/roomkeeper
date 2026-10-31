package com.roomkeeper.outbox;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A side effect of a change, saved in the change's own transaction and published to its
 * consumers afterwards, at least once. An event its consumers keep failing on is parked
 * after {@value #MAX_ATTEMPTS} attempts, and no longer retried.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

	static final int MAX_ATTEMPTS = 10;

	/** Longer errors are cut short; the log has the full stack trace. */
	private static final int MAX_ERROR_LENGTH = 1_000;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "aggregate_id", nullable = false)
	private Long aggregateId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private OutboxEventType type;

	// JSON text, stored as jsonb
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private String payload;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "sent_at")
	private Instant sentAt;

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "failed_at")
	private Instant failedAt;

	protected OutboxEvent() {
	}

	/** A new event about {@code aggregateId}, waiting to be published. */
	public OutboxEvent(Long aggregateId, OutboxEventType type, String payload, Instant createdAt) {
		this.aggregateId = aggregateId;
		this.type = type;
		this.payload = payload;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public OutboxEventType getType() {
		return type;
	}

	public String getPayload() {
		return payload;
	}

	public int getAttempts() {
		return attempts;
	}

	/** Failed too often to be retried. */
	public boolean isParked() {
		return failedAt != null;
	}

	/** Its consumer has acted on it. */
	public boolean isPublished() {
		return publishedAt != null;
	}

	/** Its consumer's stream has it: put there now, or again after the stream lost it. */
	void markSent(Instant at) {
		if (isPublished() || isParked()) {
			throw new IllegalStateException("Outbox event " + id + " is published or parked; it isn't sent again");
		}
		sentAt = at;
	}

	/** Records that its consumer acted on it. */
	void markPublished(Instant at) {
		publishedAt = at;
	}

	/**
	 * Its consumer failed on it: it's tried again, unless that was its last attempt. A reader
	 * had it, so the stream still holds it, and the wait before it's resent starts again.
	 */
	void recordFailure(String error, Instant at) {
		attempts++;
		sentAt = at;
		lastError = error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
		if (attempts >= MAX_ATTEMPTS) {
			failedAt = at;
		}
	}

}
