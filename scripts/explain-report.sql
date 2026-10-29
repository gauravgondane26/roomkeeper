-- Query plans for the hotel report and the cheapest-price recompute on realistic data, for
-- docs/query-plans.md: which inventory index they use.
--
--   docker compose up -d postgres
--   docker compose exec -T postgres psql -U roomkeeper -d roomkeeper -v ON_ERROR_STOP=1 < scripts/explain-report.sql
--
-- Loads 1,000 hotels in 50 cities, 3 room types each, a year of inventory (about 1.1
-- million rows), and 150,000 paid bookings with their payments, then explains the report
-- for one hotel over a year and one hotel's cheapest-price recompute. Everything runs in
-- one transaction that is rolled back, so the database is left as it was.

begin;

with owner as (
    insert into users (email, password_hash, name, role, created_at)
    values ('explain-report@example.com', 'not-a-real-hash', 'Explain', 'HOTEL_MANAGER', now())
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

-- 50 two-night stays per room type over the year, each paid
with guest as (
    insert into users (email, password_hash, name, role, created_at)
    values ('explain-report-guest@example.com', 'not-a-real-hash', 'Guest', 'GUEST', now())
    returning id
),
stays as (
    insert into bookings (user_id, room_id, check_in, check_out, rooms_count, status, amount, hold_expires_at,
                          created_at)
    select guest.id, r.id, current_date + s * 7, current_date + s * 7 + 2, 1, 'CONFIRMED', 2 * r.base_price,
           now(), now()
    from guest, rooms r
    join hotels h on h.id = r.hotel_id
    cross join generate_series(0, 49) as s
    where h.name like 'Explain Hotel %'
    returning id, amount
)
insert into payments (booking_id, provider_session_id, checkout_url, status, amount, currency, payment_intent_id,
                      created_at)
select id, 'cs_explain_' || id, 'https://checkout.stripe.test/' || id, 'SUCCEEDED', amount, 'inr',
       'pi_explain_' || id, now()
from stays;

analyze hotels;
analyze rooms;
analyze inventory;
analyze bookings;
analyze payments;

select (select count(*) from inventory) as inventory_rows, (select count(*) from bookings) as bookings,
       (select count(*) from payments) as payments;

-- The report query from HotelReportRepository: one hotel, a year of nights
explain (analyze, buffers)
with nights as (
    select cast(d as date) as date
    from generate_series(current_date, current_date + 365, interval '1 day') d
),
stock as (
    select i.date, sum(i.booked_count) as booked,
           sum(case when i.closed then i.booked_count else i.total_count end) as available
    from inventory i
    join rooms r on r.id = i.room_id
    where r.hotel_id = (select min(id) from hotels where name like 'Explain Hotel %')
      and i.date between current_date and current_date + 365
    group by i.date
),
kept as (
    select b.check_in, b.check_out, sum(p.amount - coalesce(p.refunded_amount, 0)) as kept
    from bookings b
    join rooms r on r.id = b.room_id
    join payments p on p.booking_id = b.id
    where r.hotel_id = (select min(id) from hotels where name like 'Explain Hotel %')
      and b.check_in <= current_date + 365 and b.check_out > current_date
      and p.status in ('SUCCEEDED', 'REFUND_PENDING', 'REFUNDED')
    group by b.id, b.check_in, b.check_out
),
revenue as (
    select n.date, sum(k.kept / (k.check_out - k.check_in)) as revenue
    from nights n
    join kept k on n.date >= k.check_in and n.date < k.check_out
    group by n.date
)
select n.date, coalesce(s.booked, 0) as rooms_booked, coalesce(s.available, 0) as rooms_available,
       coalesce(v.revenue, 0) as revenue
from nights n
left join stock s on s.date = n.date
left join revenue v on v.date = n.date
order by n.date;

-- The sellable nights HotelMinPriceUpdater reads to recompute one hotel's cheapest prices
explain (analyze, buffers)
select i.date, r.base_price, i.surge_factor, i.total_count, i.booked_count + i.reserved_count as sold_count
from inventory i
join rooms r on r.id = i.room_id
where r.hotel_id = (select min(id) from hotels where name like 'Explain Hotel %')
  and i.date between current_date and current_date + 365
  and not i.closed and i.booked_count + i.reserved_count < i.total_count;

rollback;
