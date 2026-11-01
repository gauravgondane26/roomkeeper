package com.roomkeeper.common.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import com.roomkeeper.common.error.ProblemDetailsWriter;
import com.roomkeeper.common.error.ProblemType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses request bodies over their limit with 413, before anything reads them into memory.
 * Without it every public route would read a body of any size: Tomcat's own limit covers
 * only form posts. A declared Content-Length over the limit is refused at once; a body sent
 * without one (chunked) is read until it passes the limit, then cut off.
 * <p>
 * Registered first in {@link RequestSizeLimitConfig}, ahead of Spring Security and the
 * filters that buffer bodies (idempotency).
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

	private static final String WEBHOOK = "/webhook/payment";

	private final long maxBody;

	private final long maxWebhookBody;

	private final ProblemDetailsWriter problemWriter;

	public RequestSizeLimitFilter(RequestSizeProperties properties, ProblemDetailsWriter problemWriter) {
		this.maxBody = properties.maxBody().toBytes();
		this.maxWebhookBody = properties.maxWebhookBody().toBytes();
		this.problemWriter = problemWriter;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long limit = WEBHOOK.equals(request.getRequestURI()) ? maxWebhookBody : maxBody;
		if (request.getContentLengthLong() > limit) {
			refuse(request, response, limit);
			return;
		}
		try {
			chain.doFilter(new LimitedRequest(request, limit), response);
		}
		catch (IOException | ServletException | RuntimeException ex) {
			// A filter that buffers the body (idempotency) hit the limit outside Spring MVC's handler
			if (!causedByTooLarge(ex) || response.isCommitted()) {
				throw ex;
			}
			response.reset();
			refuse(request, response, limit);
		}
	}

	private void refuse(HttpServletRequest request, HttpServletResponse response, long limit) throws IOException {
		problemWriter.write(request, response,
				ProblemType.PAYLOAD_TOO_LARGE.problem("The request body is larger than " + limit + " bytes"));
	}

	private static boolean causedByTooLarge(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof RequestTooLargeException) {
				return true;
			}
		}
		return false;
	}

	/** The request, with a body that fails once more than {@code limit} bytes are read. */
	private static final class LimitedRequest extends HttpServletRequestWrapper {

		private final long limit;

		private ServletInputStream stream;

		LimitedRequest(HttpServletRequest request, long limit) {
			super(request);
			this.limit = limit;
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			if (stream == null) {
				stream = new LimitedInputStream(super.getInputStream(), limit);
			}
			return stream;
		}

		@Override
		public BufferedReader getReader() throws IOException {
			String encoding = getCharacterEncoding();
			Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
			return new BufferedReader(new InputStreamReader(getInputStream(), charset));
		}

	}

	private static final class LimitedInputStream extends ServletInputStream {

		private final ServletInputStream in;

		private final long limit;

		private long read;

		LimitedInputStream(ServletInputStream in, long limit) {
			this.in = in;
			this.limit = limit;
		}

		@Override
		public int read() throws IOException {
			int b = in.read();
			if (b != -1) {
				count(1);
			}
			return b;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int n = in.read(buffer, offset, length);
			if (n > 0) {
				count(n);
			}
			return n;
		}

		private void count(int n) throws RequestTooLargeException {
			read += n;
			if (read > limit) {
				throw new RequestTooLargeException(limit);
			}
		}

		@Override
		public boolean isFinished() {
			return in.isFinished();
		}

		@Override
		public boolean isReady() {
			return in.isReady();
		}

		@Override
		public void setReadListener(ReadListener listener) {
			in.setReadListener(listener);
		}

	}

}
