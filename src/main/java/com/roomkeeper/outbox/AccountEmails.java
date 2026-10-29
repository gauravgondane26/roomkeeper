package com.roomkeeper.outbox;

import java.util.EnumSet;
import java.util.Set;

import com.roomkeeper.user.User;
import com.roomkeeper.user.UserRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Emails people about their accounts after signing up: a welcome for a new account, and a
 * note to the owner when someone signs up with an address that's already registered. Each
 * email's idempotency key is its outbox event's id.
 */
@Component
class AccountEmails implements OutboxConsumer {

	private final EmailSender sender;

	private final UserRepository users;

	private final JsonMapper json;

	AccountEmails(EmailSender sender, UserRepository users, JsonMapper json) {
		this.sender = sender;
		this.users = users;
		this.json = json;
	}

	@Override
	public Set<OutboxEventType> types() {
		return EnumSet.of(OutboxEventType.ACCOUNT_CREATED, OutboxEventType.SIGNUP_FOR_EXISTING_ACCOUNT);
	}

	@Override
	public void consume(OutboxEvent event) {
		String key = "outbox-" + event.getId();
		switch (event.getType()) {
			case ACCOUNT_CREATED -> {
				User user = user(json.readValue(event.getPayload(), AccountCreated.class).userId());
				sender.send(new Email(key, user.getEmail(), "Welcome to RoomKeeper",
						"Hi " + user.getName() + ", your account is ready: log in with this email and your password."));
			}
			case SIGNUP_FOR_EXISTING_ACCOUNT -> {
				User user = user(json.readValue(event.getPayload(), SignupForExistingAccount.class).userId());
				sender.send(new Email(key, user.getEmail(), "Someone tried to sign up with your email",
						"Hi " + user.getName() + ", someone tried to create an account with this address, which "
								+ "already has one. If it was you, log in instead. If not, there's nothing to do: "
								+ "nobody can use your account without your password."));
			}
			default -> throw new IllegalArgumentException("Account emails don't cover " + event.getType());
		}
	}

	private User user(long userId) {
		return users.findById(userId).orElseThrow(() -> new IllegalStateException("User " + userId + " not found"));
	}

}
