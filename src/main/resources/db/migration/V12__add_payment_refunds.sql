-- Cancellations refund all or part of a payment, through the outbox: the cancellation
-- records the refund that's due (REFUND_PENDING, with its amount), and the refund is made
-- afterwards (REFUNDED, with Stripe's refund id).
alter table payments drop constraint payments_status_check;
alter table payments add constraint payments_status_check check (status in
    ('PENDING', 'SUCCEEDED', 'EXPIRED', 'FAILED', 'REFUND_PENDING', 'REFUNDED'));

-- How much is, or is to be, given back; set when a refund is due
alter table payments add column refunded_amount numeric(10,2);

-- Refunds so far gave back everything
update payments set refunded_amount = amount where status = 'REFUNDED';

alter table payments add constraint payments_refund_within_amount
    check (refunded_amount is null or (refunded_amount > 0 and refunded_amount <= amount));
alter table payments add constraint payments_refund_has_amount
    check (status not in ('REFUND_PENDING', 'REFUNDED') or refunded_amount is not null);

-- A refund is made against the payment intent, so a payment due one must have it too
alter table payments drop constraint payments_paid_has_payment_intent;
alter table payments add constraint payments_paid_has_payment_intent
    check (status not in ('SUCCEEDED', 'REFUND_PENDING', 'REFUNDED') or payment_intent_id is not null);
