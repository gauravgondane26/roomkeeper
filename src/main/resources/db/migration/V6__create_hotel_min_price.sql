-- The cheapest bookable room per hotel per night, priced by the full rule chain. Search
-- shows "from X a night" from here instead of pricing every room type's inventory. A night
-- with nothing to sell (all closed or sold out) has no row.
create table hotel_min_price (
    hotel_id  bigint        not null references hotels (id) on delete cascade,
    date      date          not null,
    min_price numeric(10,2) not null,
    primary key (hotel_id, date)
);
