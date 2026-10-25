package com.roomkeeper.auth;

import com.roomkeeper.common.error.ApiException;
import com.roomkeeper.common.error.ProblemType;

/** Unknown, expired, already used, or revoked: the client can't tell which, by design. */
public class InvalidRefreshTokenException extends ApiException {

	public InvalidRefreshTokenException() {
		super(ProblemType.INVALID_REFRESH_TOKEN, "The refresh token is invalid or has expired; log in again");
	}

}
