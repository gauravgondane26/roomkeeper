package com.roomkeeper.booking;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The saved guests staying, by id; they replace any already on the booking. */
public record BookingGuestsRequest(@NotEmpty @Size(max = 40) List<@NotNull Long> guestIds) {
}
