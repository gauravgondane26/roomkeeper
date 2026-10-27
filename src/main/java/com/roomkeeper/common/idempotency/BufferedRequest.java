package com.roomkeeper.common.idempotency;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/** The request with its body read up front, so it can be fingerprinted and still read by the handler. */
final class BufferedRequest extends HttpServletRequestWrapper {

	private final byte[] body;

	BufferedRequest(HttpServletRequest request) throws IOException {
		super(request);
		this.body = request.getInputStream().readAllBytes();
	}

	/** Identifies the request: its method, path, query, and body. */
	String fingerprint() {
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			String query = getQueryString() == null ? "" : "?" + getQueryString();
			sha256.update((getMethod() + " " + getRequestURI() + query + "\n").getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(sha256.digest(body));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Every JVM has SHA-256", ex);
		}
	}

	@Override
	public ServletInputStream getInputStream() {
		ByteArrayInputStream in = new ByteArrayInputStream(body);
		return new ServletInputStream() {

			@Override
			public int read() {
				return in.read();
			}

			@Override
			public int read(byte[] buffer, int offset, int length) {
				return in.read(buffer, offset, length);
			}

			@Override
			public boolean isFinished() {
				return in.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener listener) {
				throw new UnsupportedOperationException("The body is already read");
			}

		};
	}

	@Override
	public BufferedReader getReader() {
		Charset charset = getCharacterEncoding() == null ? StandardCharsets.UTF_8
				: Charset.forName(getCharacterEncoding());
		return new BufferedReader(new InputStreamReader(getInputStream(), charset));
	}

}
