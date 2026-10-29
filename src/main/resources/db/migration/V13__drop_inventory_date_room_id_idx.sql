-- Nothing reads inventory by date across every room. Search, the hotel report, repricing,
-- bookings and inventory admin all reach it by room type and date, through the unique
-- (room_id, date) index (see docs/query-plans.md), so this index only cost a write on
-- every inventory change.
drop index inventory_date_room_id_idx;
