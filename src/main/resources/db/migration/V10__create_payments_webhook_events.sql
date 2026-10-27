-- One attempt to pay for a booking: a Stripe Checkout session for the booking's amount. A
-- booking can have several, one after another (a session that expired, then a new one),
-- but at most one open or paid at a time.
create table payments (
    id                  bigint generated always as identity primary key,
    -- Restrict: a booking's payments are its money trail
    booking_id          bigint        not null references bookings (id) on delete restrict,
    provider_session_id varchar(255)  not null unique,
    checkout_url        text          not null,
    status              varchar(20)   not null check (status in
                            ('PENDING', 'SUCCEEDED', 'EXPIRED', 'FAILED', 'REFUNDED')),
    -- The booking's frozen amount, in the currency's major unit
    amount              numeric(10,2) not null check (amount > 0),
    currency            varchar(3)    not null check (currency ~ '^[a-z]{3}$'),
    -- Stripe's payment, set once paid; a refund is made against it
    payment_intent_id   varchar(255),
    refund_id           varchar(255),
    created_at          timestamptz   not null,
    constraint payments_paid_has_payment_intent
        check (status not in ('SUCCEEDED', 'REFUNDED') or payment_intent_id is not null),
    constraint payments_refunded_has_refund_id
        check (status <> 'REFUNDED' or refund_id is not null)
);

-- No booking ever has two open checkout sessions, or is paid twice
create unique index payments_one_live_per_booking_idx on payments (booking_id)
    where status in ('PENDING', 'SUCCEEDED');
create index payments_booking_id_idx on payments (booking_id);

-- Stripe events already applied. The webhook inserts the event's id in the same transaction
-- as the change it makes, so a redelivered event fails the insert and changes nothing.
create table processed_webhook_events (
    event_id    varchar(255) primary key,
    received_at timestamptz  not null
);
