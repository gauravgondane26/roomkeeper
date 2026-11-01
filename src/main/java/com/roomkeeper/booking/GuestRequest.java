package com.roomkeeper.booking;

import com.roomkeeper.common.validation.NoControlCharacters;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GuestRequest(
		@NotBlank @Size(max = 100) @NoControlCharacters String name,
		@NotNull @Min(0) @Max(150) Integer age,
		@NotNull Gender gender) {
}
