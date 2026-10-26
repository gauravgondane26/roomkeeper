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

**Finding:** the design called for an index on `inventory (date, room_id)` for search, but this plan doesn't use it. Because the city filter is selective, Postgres starts from the hotels and reaches inventory through `(room_id, date)`, which the unique constraint provides anyway. The `(date, room_id)` index would only help a query that scans one date range across every room in the system (a report of all hotels' availability, for example). It still costs a write on every inventory update, so it's a candidate to drop once the week 7 reports show whether they need it.

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
