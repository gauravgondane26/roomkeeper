package com.roomkeeper.user;

import java.nio.charset.StandardCharsets;

/**
 * What a password must be, for every account however it's created. At least 15 characters,
 * as NIST SP 800-63B-4 asks when a password is the only factor (there's no MFA), with no
 * rules about which characters: long passphrases are welcome. Accounts made under the old
 * minimum of 8 keep their passwords. The upper limit is in bytes because BCrypt hashes at
 * most 72 bytes, and rejects longer passwords rather than truncate them; "é" is one
 * character but two bytes.
 */
public final class PasswordPolicy {

	public static final int MIN_CHARACTERS = 15;

	public static final int MAX_UTF8_BYTES = 72;

	private PasswordPolicy() {
	}

	public static boolean isAcceptable(String password) {
		return password != null && password.length() >= MIN_CHARACTERS
				&& password.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
	}

}
