# Query plans

How the hotel search query performs on realistic data, and which indexes it relies on. Reproduce it with [`scripts/explain-search.sql`](../scripts/explain-search.sql), which loads the data and explains the query inside a transaction that is rolled back:

```bash
docker compose up -d postgres
docker compose exec -T postgres psql -U roomkeeper -d roomkeeper -v ON_ERROR_STOP=1 < scripts/explain-search.sql
```

## Search: one city, 2 rooms for 3 nights

**Data:** 1,000 hotels across 50 cities (90% active), 3 room types each, and a year of inventory: 1,097,190 inventory rows and 365,106 cheapest-price rows. Each night is randomly part sold, so some are sold out. Postgres 18.6.

**Result:** about 1 ms to execute (1.1 to 1.4 ms across runs), touching around 560 buffers out of more than 1 million inventory rows.

What the plan does, from the inside out:

1. **`hotels_city_active_idx`** on `(lower(city), active)` finds the city's 20 active hotels with one bitmap index scan. Everything after this works on those 20 hotels, not on the whole table.
2. **`rooms_hotel_id_type_key`** (the unique index on `(hotel_id, lower(type))`) fetches each hotel's 3 room types: 60 room types.
3. **`inventory_room_date_key`** (the unique index on `(room_id, date)`) fetches each room type's 3 nights with one index range scan per room type. The open and rooms-left conditions are checked on those few rows.
4. `GROUP BY r.id ... HAVING count(*) = 3` keeps the room types free on all 3 nights, and `DISTINCT` turns them into hotels.
5. **`hotel_min_price_pkey`** on `(hotel_id, date)` reads each matching hotel's 3 cheapest prices for the "from" price, with no need to price inventory at query time.

**Finding:** the design called for an index on `inventory (date, room_id)` for search, but this plan doesn't use it. Because the city filter is selective, Postgres starts from the hotels and reaches inventory through `(room_id, date)`, which the unique constraint provides anyway. The `(date, room_id)` index would only help a query that scans one date range across every room in the system (a report of all hotels' availability, for example). It still costs a write on every inventory update, so it's a candidate to drop once the week 7 reports show whether they need it. **Week 7: they don't, so V13 dropped it** (see the report plan below).

```text
 Limit  (cost=368.32..368.33 rows=3 width=100) (actual time=1.281..1.286 rows=18.00 loops=1)
   Buffers: shared hit=410 read=158 written=23
   ->  Sort  (cost=368.32..368.33 rows=3 width=100) (actual time=1.279..1.283 rows=18.00 loops=1)
         Sort Key: (min(p.min_price)), h.id
         Sort Method: quicksort  Memory: 27kB
         Buffers: shared hit=410 read=158 written=23
         ->  GroupAggregate  (cost=350.72..368.30 rows=3 width=100) (actual time=1.132..1.266 rows=18.00 loops=1)
               Group Key: h.id
               Buffers: shared hit=410 read=158 written=23
               ->  Nested Loop Left Join  (cost=350.72..368.25 rows=3 width=73) (actual time=1.118..1.247 rows=54.00 loops=1)
                     Buffers: shared hit=410 read=158 written=23
                     ->  Nested Loop  (cost=350.29..358.34 rows=1 width=68) (actual time=1.104..1.141 rows=18.00 loops=1)
                           Buffers: shared hit=338 read=158 written=23
                           ->  Unique  (cost=350.01..350.02 rows=1 width=16) (actual time=1.089..1.096 rows=18.00 loops=1)
                                 Buffers: shared hit=284 read=158 written=23
                                 ->  Sort  (cost=350.01..350.02 rows=1 width=16) (actual time=1.089..1.092 rows=29.00 loops=1)
                                       Sort Key: r.hotel_id
                                       Sort Method: quicksort  Memory: 25kB
                                       Buffers: shared hit=284 read=158 written=23
                                       ->  GroupAggregate  (cost=349.64..350.00 rows=1 width=16) (actual time=1.069..1.084 rows=29.00 loops=1)
                                             Group Key: r.id
                                             Filter: (count(*) = 3)
                                             Rows Removed by Filter: 31
                                             Buffers: shared hit=284 read=158 written=23
                                             ->  Sort  (cost=349.64..349.69 rows=18 width=16) (actual time=1.064..1.069 rows=139.00 loops=1)
                                                   Sort Key: r.id
                                                   Sort Method: quicksort  Memory: 29kB
                                                   Buffers: shared hit=284 read=158 written=23
                                                   ->  Nested Loop  (cost=5.17..349.27 rows=18 width=16) (actual time=0.214..1.037 rows=139.00 loops=1)
                                                         Buffers: shared hit=284 read=158 written=23
                                                         ->  Nested Loop  (cost=4.74..102.33 rows=18 width=16) (actual time=0.182..0.294 rows=60.00 loops=1)
                                                               Buffers: shared hit=64 read=15 written=5
                                                               ->  Bitmap Heap Scan on hotels h_1  (cost=4.46..34.66 rows=6 width=8) (actual time=0.159..0.182 rows=20.00 loops=1)
                                                                     Recheck Cond: ((lower((city)::text) = 'explain city 7'::text) AND active)
                                                                     Filter: ((CURRENT_DATE + 30) >= ((now() AT TIME ZONE time_zone))::date)
                                                                     Heap Blocks: exact=17
                                                                     Buffers: shared hit=17 read=2 written=2
                                                                     ->  Bitmap Index Scan on hotels_city_active_idx  (cost=0.00..4.46 rows=18 width=0) (actual time=0.032..0.032 rows=20.00 loops=1)
                                                                           Index Cond: ((lower((city)::text) = 'explain city 7'::text) AND (active = true))
                                                                           Index Searches: 1
                                                                           Buffers: shared read=2 written=2
                                                               ->  Index Scan using rooms_hotel_id_type_key on rooms r  (cost=0.28..11.25 rows=3 width=16) (actual time=0.005..0.005 rows=3.00 loops=20)
                                                                     Index Cond: (hotel_id = h_1.id)
                                                                     Index Searches: 20
                                                                     Buffers: shared hit=47 read=13 written=3
                                                         ->  Index Scan using inventory_room_date_key on inventory i  (cost=0.44..13.71 rows=1 width=8) (actual time=0.010..0.012 rows=2.32 loops=60)
                                                               Index Cond: ((room_id = r.id) AND (date >= (CURRENT_DATE + 30)) AND (date < (CURRENT_DATE + 33)))
                                                               Filter: ((NOT closed) AND (((total_count - booked_count) - reserved_count) >= 2))
                                                               Rows Removed by Filter: 1
                                                               Index Searches: 60
                                                               Buffers: shared hit=220 read=143 written=18
                           ->  Index Scan using hotels_pkey on hotels h  (cost=0.28..8.29 rows=1 width=68) (actual time=0.002..0.002 rows=1.00 loops=18)
                                 Index Cond: (id = r.hotel_id)
                                 Index Searches: 18
                                 Buffers: shared hit=54
                     ->  Index Scan using hotel_min_price_pkey on hotel_min_price p  (cost=0.43..9.89 rows=3 width=13) (actual time=0.005..0.005 rows=3.00 loops=18)
                           Index Cond: ((hotel_id = h.id) AND (date >= (CURRENT_DATE + 30)) AND (date < (CURRENT_DATE + 33)))
                           Index Searches: 18
                           Buffers: shared hit=72
 Planning:
   Buffers: shared hit=108 read=10 written=10
 Planning Time: 1.103 ms
 Execution Time: 1.441 ms
```

## Hotel report and repricing

Reproduce with [`scripts/explain-report.sql`](../scripts/explain-report.sql): the same 1,000 hotels and year of inventory (1.1 million rows), plus 150,000 paid bookings with their payments, rolled back afterwards. It explains the report for one hotel over 366 nights, and the read behind one hotel's cheapest-price recompute.

Both start from the hotel's 3 room types (`rooms_hotel_id_type_key`) and reach inventory through **`inventory_room_date_key`**, one range scan per room type. Bookings come through `bookings_room_id_idx` and payments through `payments_booking_id_idx`. No query in the app reads inventory by date across every room (search, reports, repricing, bookings and inventory admin all filter by room type), so the `(date, room_id)` index went unused and V13 dropped it.

The plan also showed the report's first version re-reading bookings once per night: the revenue was a correlated subquery, so 366 nights cost 1,098 index scans of bookings. Joining the nights to the paid bookings once and grouping brought that to 3 scans and the report from about 25 ms to 13 ms. The cheapest-price recompute takes about 2 ms.

```text
 Merge Left Join  (cost=5485.35..5492.67 rows=366 width=52) (actual time=11.917..12.091 rows=366.00 loops=1)
   Merge Cond: (n.date = s.date)
   CTE nights
     ->  Function Scan on generate_series d  (cost=0.02..4.59 rows=366 width=4) (actual time=0.076..0.136 rows=366.00 loops=1)
   ->  Sort  (cost=1696.26..1697.17 rows=366 width=36) (actual time=8.003..8.055 rows=366.00 loops=1)
         Sort Key: n.date
         Sort Method: quicksort  Memory: 34kB
         ->  Hash Left Join  (cost=1672.37..1680.67 rows=366 width=36) (actual time=7.866..7.956 rows=366.00 loops=1)
               Hash Cond: (n.date = v.date)
               ->  CTE Scan on nights n  (cost=0.00..7.32 rows=366 width=4) (actual time=0.077..0.102 rows=366.00 loops=1)
                     Storage: Memory  Maximum Storage: 28kB
               ->  Hash  (cost=1669.87..1669.87 rows=200 width=36) (actual time=7.768..7.778 rows=100.00 loops=1)
                     ->  Subquery Scan on v  (cost=1665.37..1669.87 rows=200 width=36) (actual time=7.704..7.748 rows=100.00 loops=1)
                           ->  HashAggregate  (cost=1665.37..1667.87 rows=200 width=36) (actual time=7.703..7.737 rows=100.00 loops=1)
                                 Group Key: n_1.date
                                 ->  Nested Loop  (cost=636.65..1590.65 rows=5978 width=44) (actual time=0.928..7.488 rows=300.00 loops=1)
                                       Join Filter: ((n_1.date >= k.check_in) AND (n_1.date < k.check_out))
                                       Rows Removed by Join Filter: 54600
                                       ->  CTE Scan on nights n_1  (cost=0.00..7.32 rows=366 width=4) (actual time=0.000..0.135 rows=366.00 loops=1)
                                             Storage: Memory  Maximum Storage: 28kB
                                       ->  Materialize  (cost=636.65..642.16 rows=147 width=40) (actual time=0.003..0.010 rows=150.00 loops=366)
                                             Storage: Memory  Maximum Storage: 22kB
                                             ->  Subquery Scan on k  (cost=636.65..641.42 rows=147 width=40) (actual time=0.917..1.040 rows=150.00 loops=1)
                                                   ->  GroupAggregate  (cost=636.65..639.95 rows=147 width=48) (actual time=0.917..1.025 rows=150.00 loops=1)
                                                         Group Key: b.id
                                                         InitPlan 5
                                                           ->  Result  (cost=0.35..0.36 rows=1 width=8) (actual time=0.070..0.071 rows=1.00 loops=1)
                                                                 InitPlan 4
                                                                   ->  Limit  (cost=0.28..0.35 rows=1 width=8) (actual time=0.068..0.069 rows=1.00 loops=1)
                                                                         ->  Index Scan using hotels_pkey on hotels hotels_1  (cost=0.28..73.67 rows=1002 width=8) (actual time=0.067..0.067 rows=1.00 loops=1)
                                                                               Filter: ((name)::text ~~ 'Explain Hotel %'::text)
                                                                               Rows Removed by Filter: 51
                                                         ->  Sort  (cost=636.29..636.65 rows=147 width=38) (actual time=0.904..0.915 rows=150.00 loops=1)
                                                               Sort Key: b.id
                                                               Sort Method: quicksort  Memory: 30kB
                                                               ->  Nested Loop  (cost=5.38..630.99 rows=147 width=38) (actual time=0.175..0.859 rows=150.00 loops=1)
                                                                     ->  Nested Loop  (cost=4.96..550.12 rows=147 width=16) (actual time=0.157..0.428 rows=150.00 loops=1)
                                                                           ->  Index Scan using rooms_hotel_id_type_key on rooms r  (cost=0.28..11.83 rows=3 width=8) (actual time=0.086..0.089 rows=3.00 loops=1)
                                                                                 Index Cond: (hotel_id = (InitPlan 5).col1)
                                                                           ->  Bitmap Heap Scan on bookings b  (cost=4.68..178.93 rows=50 width=24) (actual time=0.040..0.093 rows=50.00 loops=3)
                                                                                 Recheck Cond: (r.id = room_id)
                                                                                 Filter: ((check_out > CURRENT_DATE) AND (check_in <= (CURRENT_DATE + 365)))
                                                                                 ->  Bitmap Index Scan on bookings_room_id_idx  (cost=0.00..4.67 rows=50 width=0) (actual time=0.029..0.029 rows=50.00 loops=3)
                                                                                       Index Cond: (room_id = r.id)
                                                                     ->  Index Scan using payments_booking_id_idx on payments p  (cost=0.42..0.54 rows=1 width=30) (actual time=0.002..0.003 rows=1.00 loops=150)
                                                                           Index Cond: (booking_id = b.id)
                                                                           Filter: ((status)::text = ANY ('{SUCCEEDED,REFUND_PENDING,REFUNDED}'::text[]))
   ->  Sort  (cost=3784.50..3785.42 rows=367 width=20) (actual time=3.905..3.927 rows=365.00 loops=1)
         Sort Key: s.date
         Sort Method: quicksort  Memory: 39kB
         ->  Subquery Scan on s  (cost=3761.53..3768.87 rows=367 width=20) (actual time=3.735..3.809 rows=365.00 loops=1)
               ->  HashAggregate  (cost=3761.53..3765.20 rows=367 width=20) (actual time=3.733..3.774 rows=365.00 loops=1)
                     Group Key: i.date
                     InitPlan 3
                       ->  Result  (cost=0.35..0.36 rows=1 width=8) (actual time=0.043..0.045 rows=1.00 loops=1)
                             InitPlan 2
                               ->  Limit  (cost=0.28..0.35 rows=1 width=8) (actual time=0.041..0.043 rows=1.00 loops=1)
                                     ->  Index Scan using hotels_pkey on hotels  (cost=0.28..73.67 rows=1002 width=8) (actual time=0.039..0.040 rows=1.00 loops=1)
                                           Filter: ((name)::text ~~ 'Explain Hotel %'::text)
                                           Rows Removed by Filter: 51
                     ->  Nested Loop  (cost=21.36..3752.98 rows=1091 width=13) (actual time=0.264..3.486 rows=1095.00 loops=1)
                           ->  Index Scan using rooms_hotel_id_type_key on rooms r_1  (cost=0.28..11.83 rows=3 width=8) (actual time=0.058..0.063 rows=3.00 loops=1)
                                 Index Cond: (hotel_id = (InitPlan 3).col1)
                           ->  Bitmap Heap Scan on inventory i  (cost=21.08..1243.41 rows=364 width=21) (actual time=0.158..1.088 rows=365.00 loops=3)
                                 Recheck Cond: ((room_id = r_1.id) AND (date >= CURRENT_DATE) AND (date <= (CURRENT_DATE + 365)))
                                 ->  Bitmap Index Scan on inventory_room_date_key  (cost=0.00..20.98 rows=364 width=0) (actual time=0.105..0.105 rows=365.00 loops=3)
                                       Index Cond: ((room_id = r_1.id) AND (date >= CURRENT_DATE) AND (date <= (CURRENT_DATE + 365)))
 Planning Time: 3.740 ms
 Execution Time: 12.638 ms
```
