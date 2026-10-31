-- A version per night, bumped by every change to it through JPA, for the optimistic way of
-- holding rooms (roomkeeper.booking.locking=optimistic): a hold reads its nights without a
-- lock and writes them back only if their version hasn't moved. Every writer of inventory
-- counts goes through the entity, so every change bumps it.
alter table inventory add column version bigint not null default 0;
