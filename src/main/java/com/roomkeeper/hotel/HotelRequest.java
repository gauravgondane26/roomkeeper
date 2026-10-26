package com.roomkeeper.hotel;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Creates a hotel, or replaces its details. Amenities are optional. */
public record HotelRequest(
		@NotBlank @Size(max = 200) String name,
		@NotBlank @Size(max = 100) String city,
		@Size(max = 30) List<@NotBlank @Size(max = 50) String> amenities) {
}
