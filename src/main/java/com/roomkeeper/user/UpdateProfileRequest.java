package com.roomkeeper.user;

import com.roomkeeper.common.validation.NoControlCharacters;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial update: null fields are left unchanged. Only the name is editable here; email
 * and role are deliberately absent, so a request can't change them.
 */
public record UpdateProfileRequest(
		@Size(max = 100) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") @NoControlCharacters
		String name) {
}
