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
 * consumers afterwards, at least once.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

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

	public Long getAggregateId() {
		return aggregateId;
	}

	public OutboxEventType getType() {
		return type;
	}

	public String getPayload() {
		return payload;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
