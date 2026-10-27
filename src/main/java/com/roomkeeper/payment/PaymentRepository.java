package com.roomkeeper.payment;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	/** The booking's payment in this status; the database allows one Pending or Succeeded at a time. */
	Optional<Payment> findByBookingIdAndStatus(Long bookingId, PaymentStatus status);

	/** Every attempt to pay for the booking, whatever became of it. */
	long countByBookingId(Long bookingId);

	Optional<Payment> findByProviderSessionId(String providerSessionId);

}
