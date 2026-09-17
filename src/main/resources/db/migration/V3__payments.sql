-- ---------------------------------------------------------------------------
-- V3: payments
--
-- A booking's money is tracked beside its status. The status says whether the stay
-- is on; payment_status says where the money is. They move together while a booking
-- is being paid for (PENDING + UNPAID, then CONFIRMED + PAID or CANCELLED + FAILED)
-- and part later: a cancelled booking stays PAID until its refund goes through.
-- ---------------------------------------------------------------------------

ALTER TABLE bookings
    -- Bookings made before this migration were confirmed without a payment: NONE.
    -- Checked against a list for the same reason as status (V1): a misspelt value
    -- would silently escape every query that looks for a specific one.
    ADD COLUMN payment_status   VARCHAR(20) NOT NULL DEFAULT 'NONE'
        CHECK (payment_status IN ('NONE', 'UNPAID', 'PAID', 'FAILED', 'REFUNDED')),
    -- Who holds the money, so a refund goes back through the same provider.
    ADD COLUMN payment_provider VARCHAR(20)
        CHECK (payment_provider IN ('STRIPE', 'SIMULATED')),
    ADD COLUMN refund_reference VARCHAR(120);

-- payment_reference (V1) now holds the provider's id for the payment, such as a
-- Stripe PaymentIntent id (pi_...). It is written before any money moves.

-- Envers records the new columns too. Permissive, like the rest of the V2 history tables.
ALTER TABLE bookings_aud
    ADD COLUMN payment_status   VARCHAR(20),
    ADD COLUMN payment_provider VARCHAR(20),
    ADD COLUMN refund_reference VARCHAR(120);

-- The payment reconciliation job looks for two kinds of booking: payments still
-- undecided (PENDING) and refunds still owed (CANCELLED but PAID). Almost every
-- booking is neither, so a partial index, which holds only the rows matching its
-- WHERE clause, stays tiny however many bookings there are, and the job never has to
-- read the whole table.
CREATE INDEX idx_bookings_payment_follow_up ON bookings (created_at)
    WHERE status = 'PENDING' OR (status = 'CANCELLED' AND payment_status = 'PAID');
