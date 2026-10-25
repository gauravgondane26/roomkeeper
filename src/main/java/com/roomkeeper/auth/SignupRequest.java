package com.roomkeeper.auth;

import com.roomkeeper.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param role optional; defaults to {@link Role#GUEST}. Anyone can sign up as a hotel
 * manager, the way anyone can become a host on a rental platform.
 */
public record SignupRequest(
		@NotBlank @Email @Size(max = 320) String email,
		// BCrypt only uses the first 72 bytes, so longer passwords would be silently truncated
		@NotBlank @Size(min = 8, max = 72) String password,
		@NotBlank @Size(max = 100) String name,
		Role role) {
}
