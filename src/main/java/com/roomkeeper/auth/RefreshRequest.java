package com.roomkeeper.auth;

import jakarta.validation.constraints.NotBlank;

/** Body of both refresh and logout. */
public record RefreshRequest(@NotBlank String refreshToken) {
}
