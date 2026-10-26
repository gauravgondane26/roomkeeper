-- Removing a saved guest hides it instead of deleting it: bookings it's on keep its name
alter table guests add column removed_at timestamptz;
