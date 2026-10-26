package com.roomkeeper.user;

import java.util.Locale;

/** Emails are stored trimmed and lower-cased, so "Asha@Example.com " and "asha@example.com" are one account. */
public final class Emails {

	private Emails() {
	}

	public static String normalize(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

}
