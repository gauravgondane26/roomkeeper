-- Guests waiting for a sold-out stay. Rooms that an expired hold or a cancellation gives back
-- go to the oldest waiter whose stay then fits, as a normal hold made in the same transaction,
-- so they never go back on sale while someone is waiting for them.
create table waitlist_entries (
    id          bigint generated always as identity primary key,
    user_id     bigint      not null references users (id),
    room_id     bigint      not null references rooms (id) on delete cascade,
    check_in    date        not null,
    check_out   date        not null,
    rooms_count int         not null check (rooms_count between 1 and 10),
    -- WAITING, then OFFERED (rooms held for the guest), LEFT (the guest gave up) or
    -- EXPIRED (the stay began first)
    status      varchar(20) not null,
    -- The hold made for the guest when rooms were offered
    booking_id  bigint      references bookings (id),
    created_at  timestamptz not null,
    offered_at  timestamptz,
    constraint waitlist_entries_stay check (check_out > check_in),
    constraint waitlist_entries_offered_with_hold check ((status = 'OFFERED') = (booking_id is not null))
);

-- One place in line per guest and stay
create unique index waitlist_entries_one_per_stay on waitlist_entries (user_id, room_id, check_in, check_out)
    where status = 'WAITING';

-- Each room type's line, oldest first
create index waitlist_entries_line on waitlist_entries (room_id, created_at, id) where status = 'WAITING';

-- A guest's own entries, newest first
create index waitlist_entries_user on waitlist_entries (user_id, created_at desc);
