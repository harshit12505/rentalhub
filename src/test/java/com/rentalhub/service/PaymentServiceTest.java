package com.rentalhub.service;

import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PaymentProvider;
import com.rentalhub.domain.model.enums.PaymentStatus;
import com.rentalhub.dto.BookingView;
import com.rentalhub.exception.PaymentFailedException;
import com.rentalhub.exception.PaymentUnavailableException;
import com.rentalhub.payment.PaymentGateway;
import com.rentalhub.payment.PaymentGatewayException;
import com.rentalhub.payment.PaymentGateways;
import com.rentalhub.payment.PaymentOutcome;
import com.rentalhub.payment.PaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The payment saga, step by step, with the provider and the database steps replaced by mocks.
 * Each test checks what the booking is left as, and that nothing is charged or released
 * that shouldn't be. BookingPaymentApiTest runs the same paths for real.
 */
class PaymentServiceTest {

    private static final long ID = 42L;
    private static final Instant CREATED = Instant.parse("2026-09-15T10:00:00.123456Z");
    private static final String REFERENCE = "pi_1";
    private static final String CARD = "pm_card_visa";

    private final PaymentGateway stripe = mock(PaymentGateway.class);
    private final BookingUpdates updates = mock(BookingUpdates.class);
    private PaymentService service;

    private final BookingView held = view(BookingStatus.PENDING, PaymentStatus.UNPAID, null, null);
    private final BookingView confirmed = view(BookingStatus.CONFIRMED, PaymentStatus.PAID, PaymentProvider.STRIPE, REFERENCE);
    private final BookingView released = view(BookingStatus.CANCELLED, PaymentStatus.FAILED, PaymentProvider.STRIPE, REFERENCE);

    @BeforeEach
    void setUp() {
        when(stripe.provider()).thenReturn(PaymentProvider.STRIPE);
        service = new PaymentService(new PaymentGateways(stripe, List.of()), updates);
        when(stripe.createPayment(any())).thenReturn(REFERENCE);
        when(updates.markPaid(ID)).thenReturn(confirmed);
        when(updates.markPaymentFailed(ID)).thenReturn(released);
    }

    // ------------------------------------------------------------- collect

    @Test
    @DisplayName("paid: create, record, confirm, then confirm the booking, in that order")
    void paid() {
        confirmWith(PaymentOutcome.succeeded());

        assertThat(service.collect(held, CARD)).isSameAs(confirmed);

        InOrder order = inOrder(stripe, updates);
        ArgumentCaptor<PaymentRequest> request = ArgumentCaptor.forClass(PaymentRequest.class);
        order.verify(stripe).createPayment(request.capture());
        order.verify(updates).recordPaymentStarted(ID, PaymentProvider.STRIPE, REFERENCE);
        order.verify(stripe).confirmPayment(REFERENCE, CARD, key("confirm"));
        order.verify(updates).markPaid(ID);
        // The listing's own total, in the listing's own currency.
        assertThat(request.getValue()).isEqualTo(
                new PaymentRequest(ID, new BigDecimal("7500.00"), Currency.INR, key("create")));
    }

    @Test
    @DisplayName("declined: the dates are released, the payment is cancelled, and the answer is a 402")
    void declined() {
        confirmWith(PaymentOutcome.declined("card_declined"));

        assertThatThrownBy(() -> service.collect(held, CARD))
                .isInstanceOfSatisfying(PaymentFailedException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo("payment.declined"));
        verify(updates).markPaymentFailed(ID);
        verify(stripe).cancelPayment(REFERENCE);
        verify(updates, never()).markPaid(anyLong());
    }

    @Test
    @DisplayName("3-D Secure needed: released too, with its own message")
    void authenticationRequired() {
        confirmWith(PaymentOutcome.authenticationRequired("requires_action"));

        assertThatThrownBy(() -> service.collect(held, CARD))
                .isInstanceOfSatisfying(PaymentFailedException.class,
                        ex -> assertThat(ex.getMessageKey()).isEqualTo("payment.authenticationRequired"));
        verify(updates).markPaymentFailed(ID);
    }

    @Test
    @DisplayName("the provider refused: released, and a 503 (try again later)")
    void notCharged() {
        confirmWith(PaymentOutcome.notCharged("AuthenticationException"));

        assertThatThrownBy(() -> service.collect(held, CARD)).isInstanceOf(PaymentUnavailableException.class);
        verify(updates).markPaymentFailed(ID);
    }

    @Test
    @DisplayName("the payment couldn't even be created: released at once, and never confirmed")
    void createFailed() {
        when(stripe.createPayment(any())).thenThrow(new PaymentGatewayException("Stripe is down"));

        assertThatThrownBy(() -> service.collect(held, CARD)).isInstanceOf(PaymentUnavailableException.class);
        verify(updates).markPaymentFailed(ID);
        verify(updates, never()).recordPaymentStarted(anyLong(), any(), anyString());
        verify(stripe, never()).confirmPayment(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("the answer was lost: the booking stays PENDING with its dates held, and nothing is released")
    void undecided() {
        confirmWith(PaymentOutcome.undecided("ApiConnectionException"));
        when(updates.view(ID)).thenReturn(held);

        assertThat(service.collect(held, CARD)).isSameAs(held);
        verify(updates, never()).markPaymentFailed(anyLong());
        verify(stripe, never()).cancelPayment(anyString());
    }

    @Test
    @DisplayName("failing to cancel a declined payment doesn't stop the dates being released")
    void cancelFailureIsNotFatal() {
        confirmWith(PaymentOutcome.declined("card_declined"));
        doThrow(new PaymentGatewayException("timeout")).when(stripe).cancelPayment(REFERENCE);

        assertThatThrownBy(() -> service.collect(held, CARD)).isInstanceOf(PaymentFailedException.class);
        verify(updates).markPaymentFailed(ID);
    }

    // -------------------------------------------------------------- refunds

    @Test
    @DisplayName("a cancelled, paid booking is refunded, with the refund's own idempotency key")
    void refund() {
        BookingView owed = view(BookingStatus.CANCELLED, PaymentStatus.PAID, PaymentProvider.STRIPE, REFERENCE);
        BookingView refunded = view(BookingStatus.CANCELLED, PaymentStatus.REFUNDED, PaymentProvider.STRIPE, REFERENCE);
        when(stripe.refund(REFERENCE, key("refund"))).thenReturn("re_1");
        when(updates.recordRefund(ID, "re_1")).thenReturn(refunded);

        assertThat(service.refundIfOwed(owed)).isSameAs(refunded);
    }

    @Test
    @DisplayName("a refund that fails leaves the booking 'refund owed', for the job to retry")
    void refundFails() {
        BookingView owed = view(BookingStatus.CANCELLED, PaymentStatus.PAID, PaymentProvider.STRIPE, REFERENCE);
        when(stripe.refund(REFERENCE, key("refund"))).thenThrow(new PaymentGatewayException("timeout"));

        assertThat(service.refundIfOwed(owed)).isSameAs(owed);
        verify(updates, never()).recordRefund(anyLong(), any());
    }

    @Test
    @DisplayName("nothing to refund: a failed payment, and a booking made before payments existed")
    void nothingToRefund() {
        BookingView prePayments = view(BookingStatus.CANCELLED, PaymentStatus.NONE, null, null);

        assertThat(service.refundIfOwed(released)).isSameAs(released);
        assertThat(service.refundIfOwed(prePayments)).isSameAs(prePayments);
        verify(stripe, never()).refund(anyString(), anyString());
    }

    // ------------------------------------------------------------ reconcile

    @Test
    @DisplayName("settle: the lost answer had succeeded, so the booking is confirmed")
    void settleSucceeded() {
        when(updates.view(ID)).thenReturn(pendingWithPayment());
        when(stripe.lookUp(REFERENCE)).thenReturn(PaymentOutcome.succeeded());

        assertThat(service.settle(ID)).isEqualTo(PaymentService.Settlement.CONFIRMED);
    }

    @Test
    @DisplayName("settle: no money was taken, so the dates are released and the payment cancelled")
    void settleNotCharged() {
        when(updates.view(ID)).thenReturn(pendingWithPayment());
        when(stripe.lookUp(REFERENCE)).thenReturn(PaymentOutcome.notCharged("requires_confirmation"));

        assertThat(service.settle(ID)).isEqualTo(PaymentService.Settlement.RELEASED);
        verify(stripe).cancelPayment(REFERENCE);
    }

    @Test
    @DisplayName("settle: still undecided at the provider, so it waits for the next run")
    void settleStillUndecided() {
        when(updates.view(ID)).thenReturn(pendingWithPayment());
        when(stripe.lookUp(REFERENCE)).thenReturn(PaymentOutcome.undecided("processing"));

        assertThat(service.settle(ID)).isEqualTo(PaymentService.Settlement.STILL_PENDING);
        verify(updates, never()).markPaid(anyLong());
        verify(updates, never()).markPaymentFailed(anyLong());
    }

    @Test
    @DisplayName("settle: stopped before a payment was recorded, so nothing was charged; no need to ask")
    void settleWithoutPayment() {
        when(updates.view(ID)).thenReturn(held);

        assertThat(service.settle(ID)).isEqualTo(PaymentService.Settlement.RELEASED);
        verify(stripe, never()).lookUp(anyString());
    }

    @Test
    @DisplayName("settle: a booking already settled, or held by a provider this instance can't reach, is left alone")
    void settleLeavesAlone() {
        when(updates.view(ID)).thenReturn(confirmed);
        assertThat(service.settle(ID)).isEqualTo(PaymentService.Settlement.ALREADY_SETTLED);

        when(updates.view(ID)).thenReturn(view(BookingStatus.PENDING, PaymentStatus.UNPAID,
                PaymentProvider.SIMULATED, "sim_pi_1"));
        assertThat(service.settle(ID)).isEqualTo(PaymentService.Settlement.STILL_PENDING);
        verify(updates, never()).markPaymentFailed(anyLong());
    }

    @Test
    @DisplayName("idempotency keys differ by step, and by the booking's creation time as well as its id")
    void idempotencyKeys() {
        BookingView sameIdAfterADatabaseWipe = new BookingView(ID, held.property(), held.guest(), held.checkIn(),
                held.checkOut(), 3, 2, held.totalAmount(), held.currency(), null, held.status(), held.payment(),
                CREATED.plusSeconds(3600));

        assertThat(PaymentService.idempotencyKey(held, "create"))
                .isEqualTo("rentalhub-booking-42-" + CREATED.toEpochMilli() + "-create")
                .isNotEqualTo(PaymentService.idempotencyKey(held, "confirm"))
                .isNotEqualTo(PaymentService.idempotencyKey(sameIdAfterADatabaseWipe, "create"));
    }

    // --------------------------------------------------------------- helpers

    private void confirmWith(PaymentOutcome outcome) {
        when(stripe.confirmPayment(REFERENCE, CARD, key("confirm"))).thenReturn(outcome);
    }

    private BookingView pendingWithPayment() {
        return view(BookingStatus.PENDING, PaymentStatus.UNPAID, PaymentProvider.STRIPE, REFERENCE);
    }

    private static String key(String step) {
        return "rentalhub-booking-42-" + CREATED.toEpochMilli() + "-" + step;
    }

    private static BookingView view(BookingStatus status, PaymentStatus paymentStatus, PaymentProvider provider,
                                    String reference) {
        return new BookingView(ID, new BookingView.Listing(1L, "Test apartment", "Chennai"),
                new BookingView.Guest(7L, "Ravi Kumar"), LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 13), 3, 2,
                new BigDecimal("7500.00"), Currency.INR, null, status,
                new BookingView.Payment(paymentStatus, provider, reference, null), CREATED);
    }
}
