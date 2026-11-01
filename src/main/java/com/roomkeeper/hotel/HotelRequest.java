package com.roomkeeper.hotel;

import java.util.List;

import com.roomkeeper.common.validation.NoControlCharacters;
import com.roomkeeper.common.validation.TimeZoneId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Creates a hotel, or replaces its details. {@code timeZone} is where the hotel is (e.g.
 * Asia/Kolkata): its nights are dates in that zone. Amenities are optional.
 */
public record HotelRequest(
		@NotBlank @Size(max = 200) @NoControlCharacters String name,
		@NotBlank @Size(max = 100) @NoControlCharacters String city,
		@NotNull @TimeZoneId String timeZone,
		@Size(max = 30) List<@NotBlank @Size(max = 50) @NoControlCharacters String> amenities) {
}
