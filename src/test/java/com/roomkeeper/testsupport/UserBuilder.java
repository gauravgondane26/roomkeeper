package com.roomkeeper.testsupport;

import java.util.UUID;

/**
 * Test data builder for users. Defaults are valid and the email is unique, so tests only
 * state what matters to them: {@code aUser().asHotelManager().build()}.
 */
public final class UserBuilder {

	private String email = "user-" + UUID.randomUUID() + "@example.com";

	private String password = "correct-horse-battery";

	private String name = "Test User";

	private String role;

	private UserBuilder() {
	}

	public static UserBuilder aUser() {
		return new UserBuilder();
	}

	public UserBuilder withEmail(String email) {
		this.email = email;
		return this;
	}

	public UserBuilder withPassword(String password) {
		this.password = password;
		return this;
	}

	public UserBuilder withName(String name) {
		this.name = name;
		return this;
	}

	public UserBuilder asHotelManager() {
		this.role = "HOTEL_MANAGER";
		return this;
	}

	public TestUser build() {
		return new TestUser(email, password, name, role);
	}

}
