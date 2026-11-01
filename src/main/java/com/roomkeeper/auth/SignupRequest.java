package com.roomkeeper.auth;

import com.roomkeeper.common.validation.MaxUtf8Bytes;
import com.roomkeeper.common.validation.NoControlCharacters;
import com.roomkeeper.user.PasswordPolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param role optional; defaults to GUEST. Anyone can sign up as a hotel manager, the way
 * anyone can become a host on a rental platform, but an admin approves each hotel before it
 * goes on sale. ADMIN can't be chosen here.
 */
public record SignupRequest(
		@NotBlank @Email @Size(max = 320) String email,
		@NotBlank @Size(min = PasswordPolicy.MIN_CHARACTERS) @MaxUtf8Bytes(PasswordPolicy.MAX_UTF8_BYTES)
		String password,
		@NotBlank @Size(max = 100) @NoControlCharacters String name,
		SignupRole role) {
}
