-- Query plan for hotel search on realistic data, for docs/query-plans.md.
--
--   docker compose up -d postgres
--   docker compose exec -T postgres psql -U roomkeeper -d roomkeeper -v ON_ERROR_STOP=1 < scripts/explain-search.sql
--
-- Loads 1,000 hotels in 50 cities (90% active), 3 room types each, and a year of
-- inventory (about 1.1 million rows, randomly part sold, some nights sold out), then
-- explains the search for one city. Everything runs in one transaction that is rolled
-- back, so the database is left as it was.

begin;

with owner as (
    insert into users (email, password_hash, name, role, created_at)
    values ('explain-search@example.com', 'not-a-real-hash', 'Explain', 'HOTEL_MANAGER', now())
    returning id
)
insert into hotels (owner_id, name, city, time_zone, amenities, active, created_at)
select owner.id, 'Explain Hotel ' || g, 'Explain City ' || (g % 50), 'Asia/Kolkata', '{wifi}', g % 10 <> 0, now()
from owner, generate_series(1, 1000) as g;

insert into rooms (hotel_id, type, base_price, capacity, total_count, created_at)
select h.id, t.type, 3000 + (h.id % 7) * 500 + t.extra, 2, 10, now()
from hotels h
cross join (values ('Standard', 0), ('Deluxe', 1500), ('Suite', 4000)) as t (type, extra)
where h.name like 'Explain Hotel %';

insert into inventory (room_id, date, total_count, booked_count)
select r.id, current_date + n, 10, floor(random() * 11)::int
from rooms r
join hotels h on h.id = r.hotel_id
cross join generate_series(0, 364) as n
where h.name like 'Explain Hotel %';

insert into hotel_min_price (hotel_id, date, min_price)
select r.hotel_id, i.date, min(r.base_price)
from inventory i
join rooms r on r.id = i.room_id
join hotels h on h.id = r.hotel_id
where h.name like 'Explain Hotel %' and i.booked_count < i.total_count
group by r.hotel_id, i.date;

analyze hotels;
analyze rooms;
analyze inventory;
analyze hotel_min_price;

select (select count(*) from hotels) as hotels, (select count(*) from rooms) as rooms,
       (select count(*) from inventory) as inventory_rows, (select count(*) from hotel_min_price) as min_price_rows;

-- The search query from HotelSearchRepository: 2 rooms for 3 nights, a month out
explain (analyze, buffers)
with available_hotels as (
    select distinct r.hotel_id
    from inventory i
    join rooms r on r.id = i.room_id
    join hotels h on h.id = r.hotel_id
    where lower(h.city) = lower('Explain City 7') and h.active
      and cast(current_date + 30 as date) >= cast(now() at time zone h.time_zone as date)
      and i.date >= current_date + 30 and i.date < current_date + 33
      and not i.closed
      and i.total_count - i.booked_count - i.reserved_count >= 2
    group by r.hotel_id, r.id
    having count(*) = 3
)
select h.id, h.name, h.city, h.amenities, min(p.min_price) as from_price
from hotels h
join available_hotels a on a.hotel_id = h.id
left join hotel_min_price p
       on p.hotel_id = h.id and p.date >= current_date + 30 and p.date < current_date + 33
group by h.id
order by from_price nulls last, h.id
limit 20 offset 0;

rollback;
