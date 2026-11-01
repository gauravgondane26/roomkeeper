package com.roomkeeper.common.web;

import java.io.IOException;

/**
 * Thrown while reading a request body that has gone past its limit. An {@link IOException},
 * so whatever is reading the body (a message converter, a filter buffering it) stops at once.
 */
public class RequestTooLargeException extends IOException {

	public RequestTooLargeException(long limit) {
		super("The request body is larger than " + limit + " bytes");
	}

}
