package com.roomkeeper.hotel;

import java.math.BigDecimal;

public record RoomResponse(Long id, Long hotelId, String type, BigDecimal basePrice, int capacity, int totalCount) {
}
