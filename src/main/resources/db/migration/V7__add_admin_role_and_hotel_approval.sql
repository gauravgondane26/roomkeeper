-- Admins review hotels before they go on sale
alter table users drop constraint users_role_check;
alter table users add constraint users_role_check check (role in ('GUEST', 'HOTEL_MANAGER', 'ADMIN'));

-- A hotel can only be activated once an admin approves it. A rejected hotel carries the
-- reason, and goes back to PENDING when its manager edits it.
alter table hotels
    add column approval_status  varchar(20)  not null default 'PENDING'
        check (approval_status in ('PENDING', 'APPROVED', 'REJECTED')),
    add column rejection_reason varchar(500),
    add column reviewed_by      bigint references users (id),
    add column reviewed_at      timestamptz;

-- Hotels already on sale were set up before reviews existed; keep them on sale
update hotels set approval_status = 'APPROVED' where active;

-- The review queue, oldest first
create index hotels_pending_review_idx on hotels (created_at) where approval_status = 'PENDING';
