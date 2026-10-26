package com.roomkeeper.testsupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test data builder for hotel requests. Defaults are valid, so tests only state what
 * matters to them: {@code aHotel().inCity("Goa").build()}.
 */
public final class HotelBuilder {

	private String name = "Seaside Inn";

	private String city = "Goa";

	private List<String> amenities = List.of("wifi", "pool");

	private HotelBuilder() {
	}

	public static HotelBuilder aHotel() {
		return new HotelBuilder();
	}

	public HotelBuilder named(String name) {
		this.name = name;
		return this;
	}

	public HotelBuilder inCity(String city) {
		this.city = city;
		return this;
	}

	public HotelBuilder withAmenities(String... amenities) {
		this.amenities = List.of(amenities);
		return this;
	}

	public Map<String, Object> build() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("city", city);
		body.put("amenities", amenities);
		return body;
	}

}
