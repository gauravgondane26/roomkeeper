package com.roomkeeper.common.web;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * {@code roomkeeper.request-size}: the largest request body accepted.
 *
 * @param maxBody any API route; the largest real request (a hotel with 30 amenities) is a
 * few kilobytes
 * @param maxWebhookBody Stripe's webhook, whose events are larger but still small
 */
@Validated
@ConfigurationProperties("roomkeeper.request-size")
public record RequestSizeProperties(@NotNull DataSize maxBody, @NotNull DataSize maxWebhookBody) {
}
