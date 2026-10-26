package com.roomkeeper.hotel;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A room type in a hotel, such as "Deluxe King". {@code totalCount} is how many of them exist. */
@Entity
@Table(name = "rooms")
public class Room {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "hotel_id", nullable = false)
	private Hotel hotel;

	@Column(nullable = false, length = 100)
	private String type;

	@Column(name = "base_price", nullable = false, precision = 10, scale = 2)
	private BigDecimal basePrice;

	@Column(nullable = false)
	private int capacity;

	@Column(name = "total_count", nullable = false)
	private int totalCount;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected Room() {
	}

	public Room(Hotel hotel, String type, BigDecimal basePrice, int capacity, int totalCount, Instant createdAt) {
		this.hotel = hotel;
		this.type = type;
		this.basePrice = basePrice;
		this.capacity = capacity;
		this.totalCount = totalCount;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Hotel getHotel() {
		return hotel;
	}

	public String getType() {
		return type;
	}

	public BigDecimal getBasePrice() {
		return basePrice;
	}

	public int getCapacity() {
		return capacity;
	}

	public int getTotalCount() {
		return totalCount;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
