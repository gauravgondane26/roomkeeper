-- Events reach their consumers through a Redis stream each (outbox:{consumer}). sent_at is
-- when the event's stream last had it: set when it's sent, and again when a consumer fails
-- on it. published_at now means its consumer acted on it and committed. A row sent but not
-- published for too long is resent, so a stream Redis lost delays an event, never loses it.
alter table outbox_events add column sent_at timestamptz;

-- The waiting index (published_at is null and failed_at is null) stays: the rows still to be
-- published are few, and both the unsent queue and the sweep for stale sends read through it.
