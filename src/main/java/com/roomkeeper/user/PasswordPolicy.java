package com.roomkeeper.user;

import java.nio.charset.StandardCharsets;

/**
 * What a password must be, for every account however it's created. The upper limit is in
 * bytes because BCrypt hashes at most 72 bytes, and rejects longer passwords rather than
 * truncate them; "é" is one character but two bytes.
 */
public final class PasswordPolicy {

	public static final int MIN_CHARACTERS = 8;

	public static final int MAX_UTF8_BYTES = 72;

	private PasswordPolicy() {
	}

	public static boolean isAcceptable(String password) {
		return password != null && password.length() >= MIN_CHARACTERS
				&& password.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
	}

}
