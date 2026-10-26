package com.roomkeeper.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code roomkeeper.admin}: the first admin account, created at startup if it doesn't exist.
 * Leave the email empty to create none.
 */
@ConfigurationProperties("roomkeeper.admin")
public record AdminProperties(String email, String password) {
}
