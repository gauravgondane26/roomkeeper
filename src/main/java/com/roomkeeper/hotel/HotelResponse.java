package com.roomkeeper.hotel;

import java.util.List;

public record HotelResponse(Long id, String name, String city, List<String> amenities, boolean active) {
}
