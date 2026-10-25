package com.roomkeeper.auth;

import java.time.Duration;

public record AccessToken(String value, Duration expiresIn) {
}
