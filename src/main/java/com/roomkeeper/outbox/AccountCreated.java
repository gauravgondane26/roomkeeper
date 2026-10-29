package com.roomkeeper.outbox;

/** Someone signed up with a new email: the account is theirs, and they're welcomed. */
public record AccountCreated(long userId) implements OutboxPayload {

	@Override
	public OutboxEventType type() {
		return OutboxEventType.ACCOUNT_CREATED;
	}

	@Override
	public long aggregateId() {
		return userId;
	}

}
