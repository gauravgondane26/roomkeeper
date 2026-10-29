package com.roomkeeper.booking;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Which page of bookings: {@code page} from 0, 20 bookings a page by default. */
public record BookingPageRequest(@Min(0) Integer page, @Min(1) @Max(50) Integer size) {
}
