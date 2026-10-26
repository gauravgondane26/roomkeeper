package com.roomkeeper.testsupport;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test data builder for room type requests. Defaults are valid, so tests only state what
 * matters to them: {@code aRoom().withTotalCount(3).build()}.
 */
public final class RoomBuilder {

	private String type = "Deluxe";

	private BigDecimal basePrice = new BigDecimal("4500.00");

	private int capacity = 2;

	private int totalCount = 5;

	private RoomBuilder() {
	}

	public static RoomBuilder aRoom() {
		return new RoomBuilder();
	}

	public RoomBuilder ofType(String type) {
		this.type = type;
		return this;
	}

	public RoomBuilder pricedAt(String basePrice) {
		this.basePrice = new BigDecimal(basePrice);
		return this;
	}

	public RoomBuilder sleeping(int capacity) {
		this.capacity = capacity;
		return this;
	}

	public RoomBuilder withTotalCount(int totalCount) {
		this.totalCount = totalCount;
		return this;
	}

	public Map<String, Object> build() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", type);
		body.put("basePrice", basePrice);
		body.put("capacity", capacity);
		body.put("totalCount", totalCount);
		return body;
	}

}
