create table hotels (
    id         bigint generated always as identity primary key,
    owner_id   bigint       not null references users (id),
    name       varchar(200) not null,
    city       varchar(100) not null,
    amenities  text[]       not null default '{}',
    -- Only active hotels have inventory and show up in search
    active     boolean      not null default false,
    created_at timestamptz  not null
);

create index hotels_owner_id_idx on hotels (owner_id);
-- Search filters active hotels by city; lower() so "Goa" and "goa" match
create index hotels_city_active_idx on hotels (lower(city), active);

-- A room type (e.g. "Deluxe King"), not a physical room: total_count says how many exist
create table rooms (
    id          bigint generated always as identity primary key,
    hotel_id    bigint        not null references hotels (id) on delete cascade,
    type        varchar(100)  not null,
    base_price  numeric(10,2) not null check (base_price > 0),
    capacity    integer       not null check (capacity > 0),
    total_count integer       not null check (total_count >= 0),
    created_at  timestamptz   not null
);

create unique index rooms_hotel_id_type_key on rooms (hotel_id, lower(type));

-- One row per room type per night. Holds (reserved_count) and bookings (booked_count)
-- are counted here, so the database can refuse an oversell on its own.
create table inventory (
    id             bigint generated always as identity primary key,
    room_id        bigint       not null references rooms (id) on delete cascade,
    date           date         not null,
    total_count    integer      not null check (total_count >= 0),
    booked_count   integer      not null default 0 check (booked_count >= 0),
    reserved_count integer      not null default 0 check (reserved_count >= 0),
    surge_factor   numeric(4,2) not null default 1.00 check (surge_factor > 0),
    closed         boolean      not null default false,
    constraint inventory_room_date_key unique (room_id, date),
    -- The last line of defense: even buggy application code can't sell more rooms than exist
    constraint inventory_not_oversold check (booked_count + reserved_count <= total_count)
);

-- Search scans every room's nights in a date range
create index inventory_date_room_id_idx on inventory (date, room_id);
