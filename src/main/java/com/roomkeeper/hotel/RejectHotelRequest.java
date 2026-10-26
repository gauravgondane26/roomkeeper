package com.roomkeeper.hotel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why the hotel can't go on sale as it is; the manager sees this. */
public record RejectHotelRequest(@NotBlank @Size(max = 500) String reason) {
}
