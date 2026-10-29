package com.roomkeeper.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.roomkeeper.pricing.Night;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One room type on one night: how many exist, how many are booked, and how many are held
 * by unpaid bookings. The table's check constraint keeps booked + reserved within the total.
 */
@Entity
@Table(name = "inventory")
public class Inventory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "room_id", nullable = false)
	private Long roomId;

	@Column(nullable = false)
	private LocalDate date;

	@Column(name = "total_count", nullable = false)
	private int totalCount;

	// Set by hand in inventory admin, so changes to the room type's total skip this night
	@Column(name = "total_overridden", nullable = false)
	private boolean totalOverridden;

	@Column(name = "booked_count", nullable = false)
	private int bookedCount;

	@Column(name = "reserved_count", nullable = false)
	private int reservedCount;

	@Column(name = "surge_factor", nullable = false, precision = 4, scale = 2)
	private BigDecimal surgeFactor = BigDecimal.ONE;

	@Column(nullable = false)
	private boolean closed;

	protected Inventory() {
	}

	public Inventory(Long roomId, LocalDate date, int totalCount) {
		this.roomId = roomId;
		this.date = date;
		this.totalCount = totalCount;
	}

	public LocalDate getDate() {
		return date;
	}

	public int getTotalCount() {
		return totalCount;
	}

	public boolean isTotalOverridden() {
		return totalOverridden;
	}

	public int getBookedCount() {
		return bookedCount;
	}

	public int getReservedCount() {
		return reservedCount;
	}

	/** Rooms still free to sell on this night, closed or not. */
	public int getAvailable() {
		return totalCount - sold();
	}

	/** Rooms booked or held on this night: the total can't go below this. */
	public int sold() {
		return bookedCount + reservedCount;
	}

	/**
	 * A stay of {@code rooms} rooms from {@code checkIn} up to {@code checkOut} can be sold on
	 * these, its nights: there's a row for every night (a night without one isn't on sale yet),
	 * and each is open with the rooms free.
	 */
	public static boolean canSellStay(List<Inventory> nights, LocalDate checkIn, LocalDate checkOut, int rooms) {
		return nights.size() == ChronoUnit.DAYS.between(checkIn, checkOut)
				&& nights.stream().allMatch(night -> night.canSell(rooms));
	}

	/** Some night has no room left to sell. */
	public static boolean anySoldOut(List<Inventory> nights) {
		return nights.stream().anyMatch(night -> night.getAvailable() == 0);
	}

	/** Open, with at least {@code rooms} rooms free. */
	public boolean canSell(int rooms) {
		return !closed && getAvailable() >= rooms;
	}

	/** Holds rooms for an unpaid booking. Check {@link #canSell} first. */
	public void hold(int rooms) {
		if (!canSell(rooms)) {
			throw new IllegalStateException("Can't hold " + rooms + " rooms on " + date + ": " + getAvailable()
					+ " free" + (closed ? ", closed" : ""));
		}
		reservedCount += rooms;
	}

	/** A held booking was paid for: its rooms move from held to booked. */
	public void confirm(int rooms) {
		if (reservedCount < rooms) {
			throw new IllegalStateException("Can't confirm " + rooms + " rooms on " + date + ": only " + reservedCount
					+ " held");
		}
		reservedCount -= rooms;
		bookedCount += rooms;
	}

	/**
	 * Books rooms straight away, without a hold: a payment that arrived after its booking's
	 * hold was released. Check {@link #canSell} first.
	 */
	public void book(int rooms) {
		if (!canSell(rooms)) {
			throw new IllegalStateException("Can't book " + rooms + " rooms on " + date + ": " + getAvailable()
					+ " free" + (closed ? ", closed" : ""));
		}
		bookedCount += rooms;
	}

	/** Puts rooms of a cancelled booking back on sale. */
	public void unbook(int rooms) {
		if (bookedCount < rooms) {
			throw new IllegalStateException("Can't unbook " + rooms + " rooms on " + date + ": only " + bookedCount
					+ " booked");
		}
		bookedCount -= rooms;
	}

	/** Gives back rooms held by a booking that expired. */
	public void release(int rooms) {
		if (reservedCount < rooms) {
			throw new IllegalStateException("Can't release " + rooms + " rooms on " + date + ": only " + reservedCount
					+ " held");
		}
		reservedCount -= rooms;
	}

	/** This night as the pricing rules see it, for a room type at {@code basePrice}. */
	public Night toNight(BigDecimal basePrice) {
		return new Night(date, basePrice, surgeFactor, totalCount, sold());
	}

	public BigDecimal getSurgeFactor() {
		return surgeFactor;
	}

	public boolean isClosed() {
		return closed;
	}

	public void markClosed(boolean closed) {
		this.closed = closed;
	}

	public void changeSurgeFactor(BigDecimal surgeFactor) {
		this.surgeFactor = surgeFactor;
	}

	/** A manager set this night's total by hand: it no longer follows the room type's total. */
	public void overrideTotalCount(int totalCount) {
		this.totalCount = totalCount;
		this.totalOverridden = true;
	}

	/** The room type's total changed and this night follows it. */
	public void followRoomTotal(int totalCount) {
		this.totalCount = totalCount;
	}

}
