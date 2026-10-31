package com.roomkeeper.common;

/**
 * Makes a value from a request safe to put in a log line. Line breaks and other control
 * characters become {@code _}, so a header or path a client chose can't start a forged log
 * line or recolour a terminal; long values are cut to {@value #MAX_LENGTH} characters.
 */
public final class LogSafe {

	private static final int MAX_LENGTH = 200;

	private LogSafe() {
	}

	public static String of(String value) {
		if (value == null) {
			return null;
		}
		String cut = value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) + "..." : value;
		// \p{Cntrl} covers \r and \n too; replacing them by name is what CodeQL's log-injection
		// check recognizes as removing line breaks
		return cut.replace('\r', '_').replace('\n', '_').replaceAll("\\p{Cntrl}", "_");
	}

}
