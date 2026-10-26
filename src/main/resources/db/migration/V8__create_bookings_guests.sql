-- One booking is a stay in rooms_count rooms of one room type, from the check_in night up
-- to the check_out morning. Until it's confirmed it is a hold, counted in the nights'
-- reserved_count, that runs out at hold_expires_at.
create table bookings (
    id              bigint generated always as identity primary key,
    user_id         bigint        not null references users (id),
    -- Restrict, not cascade: a room type with bookings can't be deleted, and nor can its
    -- hotel, since deleting a hotel cascades to its room types
    room_id         bigint        not null references rooms (id) on delete restrict,
    check_in        date          not null,
    check_out       date          not null,
    rooms_count     integer       not null check (rooms_count > 0),
    status          varchar(20)   not null check (status in
                        ('RESERVED', 'GUESTS_ADDED', 'PAYMENT_PENDING', 'CONFIRMED', 'EXPIRED', 'CANCELLED')),
    -- The price quoted when the rooms were held; later price changes don't touch it
    amount          numeric(10,2) not null check (amount > 0),
    hold_expires_at timestamptz   not null,
    -- Optimistic locking, for the expiry job racing the payment webhook
    version         bigint        not null default 0,
    created_at      timestamptz   not null,
    constraint bookings_check_out_after_check_in check (check_out > check_in)
);

-- A user's bookings, newest first
create index bookings_user_id_idx on bookings (user_id, created_at desc);
-- Deleting a room type checks for its bookings
create index bookings_room_id_idx on bookings (room_id);
-- The expiry job finds unpaid holds that have run out
create index bookings_open_holds_idx on bookings (hold_expires_at)
    where status in ('RESERVED', 'GUESTS_ADDED', 'PAYMENT_PENDING');

-- Travellers a user has saved, to put on their bookings
create table guests (
    id      bigint generated always as identity primary key,
    user_id bigint       not null references users (id),
    name    varchar(100) not null,
    age     integer      not null check (age >= 0 and age <= 150),
    gender  varchar(20)  not null check (gender in ('FEMALE', 'MALE', 'OTHER'))
);

create index guests_user_id_idx on guests (user_id);

create table booking_guests (
    booking_id bigint not null references bookings (id) on delete cascade,
    -- A guest on a booking stays, so the booking's history keeps its names
    guest_id   bigint not null references guests (id),
    primary key (booking_id, guest_id)
);

-- Deleting a guest checks for bookings that have them
create index booking_guests_guest_id_idx on booking_guests (guest_id);
