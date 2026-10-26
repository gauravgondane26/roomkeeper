package com.roomkeeper.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A stay of {@code roomsCount} rooms of one room type, from the check-in night up to the
 * check-out morning. The amount is the price quoted when the rooms were held, so a later
 * price change doesn't touch it.
 */
@Entity
@Table(name = "bookings")
public class Booking {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "room_id", nullable = false)
	private Long roomId;

	@Column(name = "check_in", nullable = false)
	private LocalDate checkIn;

	@Column(name = "check_out", nullable = false)
	private LocalDate checkOut;

	@Column(name = "rooms_count", nullable = false)
	private int roomsCount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private BookingStatus status = BookingStatus.RESERVED;

	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal amount;

	@Column(name = "hold_expires_at", nullable = false)
	private Instant holdExpiresAt;

	@ManyToMany
	@JoinTable(name = "booking_guests", joinColumns = @JoinColumn(name = "booking_id"),
			inverseJoinColumns = @JoinColumn(name = "guest_id"))
	private Set<Guest> guests = new HashSet<>();

	// The hold expiry job and the payment webhook can race on one booking; the loser retries
	@Version
	private long version;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Booking() {
	}

	/** A new hold on the rooms, until {@code holdExpiresAt}. */
	public Booking(Long userId, Long roomId, LocalDate checkIn, LocalDate checkOut, int roomsCount, BigDecimal amount,
			Instant holdExpiresAt, Instant createdAt) {
		this.userId = userId;
		this.roomId = roomId;
		this.checkIn = checkIn;
		this.checkOut = checkOut;
		this.roomsCount = roomsCount;
		this.amount = amount;
		this.holdExpiresAt = holdExpiresAt;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public Long getRoomId() {
		return roomId;
	}

	public LocalDate getCheckIn() {
		return checkIn;
	}

	public LocalDate getCheckOut() {
		return checkOut;
	}

	public int getRoomsCount() {
		return roomsCount;
	}

	public BookingStatus getStatus() {
		return status;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public Instant getHoldExpiresAt() {
		return holdExpiresAt;
	}

	public Set<Guest> getGuests() {
		return Set.copyOf(guests);
	}

}
