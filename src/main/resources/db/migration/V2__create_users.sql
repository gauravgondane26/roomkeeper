create table users (
    id            bigint generated always as identity primary key,
    email         varchar(320) not null,
    password_hash varchar(100) not null,
    name          varchar(100) not null,
    role          varchar(20)  not null check (role in ('GUEST', 'HOTEL_MANAGER')),
    created_at    timestamptz  not null
);

-- Emails are stored lower-cased by the app; the index makes uniqueness case-insensitive
-- even if something writes mixed case directly.
create unique index users_email_lower_key on users (lower(email));
