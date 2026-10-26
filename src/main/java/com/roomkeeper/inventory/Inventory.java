package com.roomkeeper.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;

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

	public Long getId() {
		return id;
	}

	public Long getRoomId() {
		return roomId;
	}

	public LocalDate getDate() {
		return date;
	}

	public int getTotalCount() {
		return totalCount;
	}

	public int getBookedCount() {
		return bookedCount;
	}

	public int getReservedCount() {
		return reservedCount;
	}

	public BigDecimal getSurgeFactor() {
		return surgeFactor;
	}

	public boolean isClosed() {
		return closed;
	}

}
