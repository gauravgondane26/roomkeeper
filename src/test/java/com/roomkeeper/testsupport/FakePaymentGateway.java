package com.roomkeeper.testsupport;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.roomkeeper.payment.CheckoutRequest;
import com.roomkeeper.payment.CheckoutSession;
import com.roomkeeper.payment.PaymentGateway;

/**
 * Stands in for Stripe in integration tests. Checkout sessions, expiries and refunds are
 * kept in memory and keyed the way Stripe keys them: the same idempotency key gets the same
 * session or refund back. One instance serves every test in a Spring context, so tests look
 * up their own booking's calls rather than counting all of them.
 */
public class FakePaymentGateway implements PaymentGateway {

	private final AtomicLong ids = new AtomicLong();

	private final Map<String, CheckoutSession> sessionsByKey = new ConcurrentHashMap<>();

	private final List<CheckoutRequest> checkouts = new CopyOnWriteArrayList<>();

	private final Set<String> expiredSessions = ConcurrentHashMap.newKeySet();

	private final Map<String, String> refundsByKey = new ConcurrentHashMap<>();

	private final List<String> refundedPaymentIntents = new CopyOnWriteArrayList<>();

	@Override
	public CheckoutSession createCheckoutSession(CheckoutRequest request) {
		return sessionsByKey.computeIfAbsent(request.idempotencyKey(), key -> {
			checkouts.add(request);
			String id = "cs_test_fake_" + ids.incrementAndGet();
			return new CheckoutSession(id, "https://checkout.stripe.test/c/pay/" + id);
		});
	}

	@Override
	public void expireCheckoutSession(String sessionId) {
		expiredSessions.add(sessionId);
	}

	@Override
	public String refund(String paymentIntentId, String idempotencyKey) {
		return refundsByKey.computeIfAbsent(idempotencyKey, key -> {
			refundedPaymentIntents.add(paymentIntentId);
			return "re_test_fake_" + ids.incrementAndGet();
		});
	}

	/** The checkout sessions actually opened for the booking, oldest first. */
	public List<CheckoutRequest> checkoutsFor(long bookingId) {
		return checkouts.stream().filter(checkout -> checkout.bookingId() == bookingId).toList();
	}

	public boolean wasExpired(String sessionId) {
		return expiredSessions.contains(sessionId);
	}

	/** How many refunds were actually made against the payment. */
	public long refundsOf(String paymentIntentId) {
		return refundedPaymentIntents.stream().filter(paymentIntentId::equals).count();
	}

}
