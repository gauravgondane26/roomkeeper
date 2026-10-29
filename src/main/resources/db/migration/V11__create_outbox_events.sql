-- Side effects of a change (emails, notifications), written in the same transaction as the
-- change, so one is never lost and never sent for a change that rolled back. A scheduled
-- publisher hands waiting events to their consumers, at least once.
create table outbox_events (
    id           bigint generated always as identity primary key,
    -- What the event is about: a booking for booking events, a user for account ones
    aggregate_id bigint        not null,
    type         varchar(50)   not null,
    payload      jsonb         not null,
    created_at   timestamptz   not null,
    -- Set once a consumer has taken it
    published_at timestamptz,
    -- Failed attempts so far; after too many the event is parked (failed_at set) with its
    -- last error, and no longer retried
    attempts     int           not null default 0 check (attempts >= 0),
    last_error   text,
    failed_at    timestamptz,
    constraint outbox_events_published_or_failed check (published_at is null or failed_at is null)
);

-- The publisher's queue: events still waiting, oldest first
create index outbox_events_waiting_idx on outbox_events (id) where published_at is null and failed_at is null;
