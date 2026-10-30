-- The pricing settings hotel_min_price was last worked out with, as one canonical line
-- (PricingProperties.fingerprint), so a deploy that changes them knows to re-price every
-- hotel. One row at most: the key can only be true.
create table pricing_settings (
    id          boolean     primary key default true check (id),
    fingerprint text        not null,
    updated_at  timestamptz not null
);
