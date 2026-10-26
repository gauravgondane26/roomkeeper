package com.roomkeeper.auth;

import com.roomkeeper.common.validation.MaxUtf8Bytes;
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
		// BCrypt hashes at most 72 bytes, and rejects longer passwords rather than truncate them
		@NotBlank @Size(min = 8) @MaxUtf8Bytes(72) String password,
		@NotBlank @Size(max = 100) String name,
		SignupRole role) {
}
