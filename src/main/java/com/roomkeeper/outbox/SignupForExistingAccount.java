package com.roomkeeper.outbox;

/**
 * Someone signed up with an email that already has an account. Signup answers as it would
 * for a new email, so it can't reveal who's registered; the account's owner is told instead.
 */
public record SignupForExistingAccount(long userId) implements OutboxPayload {

	@Override
	public OutboxEventType type() {
		return OutboxEventType.SIGNUP_FOR_EXISTING_ACCOUNT;
	}

	@Override
	public long aggregateId() {
		return userId;
	}

}
