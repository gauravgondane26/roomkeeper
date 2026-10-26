-- The hotel's IANA time zone (e.g. Asia/Kolkata). Inventory nights are dates in this zone,
-- so "today" for a hotel starts at its own midnight. Existing hotels were created when
-- every date was UTC, so they keep UTC; new hotels must say where they are.
alter table hotels add column time_zone varchar(64) not null default 'UTC';
alter table hotels alter column time_zone drop default;
