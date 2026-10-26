package com.roomkeeper.booking;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code roomkeeper.booking}: how long an unpaid booking holds its rooms. */
@ConfigurationProperties("roomkeeper.booking")
public record BookingProperties(Duration holdDuration) {
}
