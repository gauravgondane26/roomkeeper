package com.roomkeeper.outbox;

/** Sends emails; the same idempotency key twice sends one. */
public interface EmailSender {

	void send(Email email);

}
