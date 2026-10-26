-- True once a manager sets a night's total by hand in inventory admin. A later change to
-- the room type's total then skips that night instead of overwriting the manager's choice.
alter table inventory add column total_overridden boolean not null default false;
