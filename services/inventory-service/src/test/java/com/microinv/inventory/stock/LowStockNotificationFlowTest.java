package com.microinv.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.microinv.inventory.error.ApiException;
import com.microinv.inventory.notification.NotificationRequest;
import com.microinv.inventory.support.AbstractIntegrationTest;
import com.microinv.inventory.support.JwtTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.MediaType;

/** Aviso LOW_STOCK: solo al cruzar el umbral, una vez, tras el commit y sin poder romper la operacion. */
class LowStockNotificationFlowTest extends AbstractIntegrationTest {

    @Autowired
    private StockService stockService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static ReserveRequest.Item item(UUID productId, int quantity) {
        return new ReserveRequest.Item(productId, quantity);
    }

    private NotificationRequest singleSentNotification() {
        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationClient, times(1)).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void crossingTheThresholdOnReserveSendsExactlyOneBroadcastNotificationWithNullActor() {
        UUID p = insertProduct("LS-1", 10, 5);

        stockService.reserve(UUID.randomUUID(), List.of(item(p, 5))); // 10 -> 5: cruza (5 <= 5)

        NotificationRequest sent = singleSentNotification();
        assertThat(sent.type()).isEqualTo("LOW_STOCK");
        assertThat(sent.entityType()).isEqualTo("PRODUCT");
        assertThat(sent.entityId()).isEqualTo(p);
        assertThat(sent.targetUserId()).isNull();
        assertThat(sent.actorUserId()).isNull();
        assertThat(sent.title()).isNotBlank().hasSizeLessThanOrEqualTo(120);
        assertThat(sent.message()).isNotBlank().hasSizeLessThanOrEqualTo(500).contains("LS-1");
    }

    @Test
    void doesNotNotifyAgainWhileAlreadyLow() {
        UUID p = insertProduct("LS-2", 10, 5);
        stockService.reserve(UUID.randomUUID(), List.of(item(p, 5)));  // 10 -> 5: aviso
        stockService.reserve(UUID.randomUUID(), List.of(item(p, 1)));  // 5 -> 4: ya estaba bajo
        stockService.reserve(UUID.randomUUID(), List.of(item(p, 4)));  // 4 -> 0: ya estaba bajo

        singleSentNotification();
    }

    @Test
    void doesNotNotifyWhenStaysAboveThresholdOrWasAlreadyBelow() {
        UUID above = insertProduct("LS-3", 20, 5);
        UUID below = insertProduct("LS-4", 3, 5);

        stockService.reserve(UUID.randomUUID(), List.of(item(above, 10), item(below, 1))); // 20->10, 3->2

        verify(notificationClient, never()).send(any());
    }

    @Test
    void notifiesAgainAfterStockRecoversAndCrossesDownAgain() {
        UUID p = insertProduct("LS-5", 6, 5);
        UUID order = UUID.randomUUID();
        stockService.reserve(order, List.of(item(p, 1)));   // 6 -> 5: aviso 1
        stockService.release(order);                        // 5 -> 6 (liberar nunca avisa)
        stockService.reserve(UUID.randomUUID(), List.of(item(p, 1))); // 6 -> 5: aviso 2

        verify(notificationClient, times(2)).send(any());
    }

    @Test
    void oneNotificationPerProductThatCrosses() {
        UUID a = insertProduct("LS-6A", 10, 5);
        UUID b = insertProduct("LS-6B", 10, 5);
        UUID c = insertProduct("LS-6C", 50, 5);

        stockService.reserve(UUID.randomUUID(), List.of(item(a, 6), item(b, 9), item(c, 1)));

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationClient, times(2)).send(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationRequest::entityId).containsExactlyInAnyOrder(a, b);
    }

    /**
     * OJO: este test solo comprueba el comportamiento de extremo a extremo (reserva rechazada: ni stock ni aviso).
     * La reserva falla en la validacion, ANTES de publicar ningun evento, asi que NO demuestra que el aviso se
     * envie despues del commit: eso lo demuestran los dos tests siguientes, que publican el evento dentro de
     * una transaccion real.
     */
    @Test
    void rejectedReservationChangesNothingAndSendsNothing() {
        UUID crossing = insertProduct("LS-7A", 10, 5);
        UUID short1 = insertProduct("LS-7B", 1, 5);

        assertThatThrownBy(() -> stockService.reserve(UUID.randomUUID(), List.of(item(crossing, 8), item(short1, 2))))
                .isInstanceOf(ApiException.class);

        verify(notificationClient, never()).send(any());
        assertThat(stockOf(crossing)).isEqualTo(10);
    }

    private LowStockEvent sampleEvent() {
        return new LowStockEvent(UUID.randomUUID(), "SKU-EVT", "Evento", 2, 5, null);
    }

    @Test
    void anEventPublishedInsideATransactionThatRollsBackIsNeverSent() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // rollback explicito
        tx.executeWithoutResult(status -> {
            eventPublisher.publishEvent(sampleEvent());
            verify(notificationClient, never()).send(any()); // ni siquiera durante la transaccion
            status.setRollbackOnly();
        });
        // rollback por excepcion
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            eventPublisher.publishEvent(sampleEvent());
            throw new IllegalStateException("boom after publishing");
        })).isInstanceOf(IllegalStateException.class);

        verify(notificationClient, never()).send(any());
    }

    @Test
    void anEventPublishedInsideATransactionIsSentOnlyAfterTheCommit() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        LowStockEvent event = sampleEvent();

        tx.executeWithoutResult(status -> {
            eventPublisher.publishEvent(event);
            // La transaccion sigue abierta: aun no se ha enviado nada (AFTER_COMMIT, no "en el momento").
            verify(notificationClient, never()).send(any());
        });

        // Ya confirmada: sale exactamente una notificacion.
        NotificationRequest sent = singleSentNotification();
        assertThat(sent.entityId()).isEqualTo(event.productId());
    }

    @Test
    void aFailingNotificationClientNeverBreaksTheReservation() {
        doThrow(new IllegalStateException("notification-service down")).when(notificationClient).send(any());
        UUID p = insertProduct("LS-8", 10, 5);
        UUID order = UUID.randomUUID();

        ReserveResponse response = stockService.reserve(order, List.of(item(p, 6)));

        assertThat(response.items()).hasSize(1);
        assertThat(stockOf(p)).isEqualTo(4);
        assertThat(countMovements(order, MovementType.RESERVE)).isEqualTo(1);
        verify(notificationClient, times(1)).send(any());
        // y una reserva idempotente posterior sigue funcionando
        assertThat(stockService.reserve(order, List.of(item(p, 6)))).isEqualTo(response);
    }

    @Test
    void negativeAdjustmentCrossingTheThresholdNotifiesWithTheCurrentUserAsActor() throws Exception {
        UUID p = insertProduct("LS-9", 10, 5);
        UUID user = UUID.randomUUID();

        mvc.perform(post("/api/products/" + p + "/stock-adjustments")
                        .header("Authorization", "Bearer " + JwtTestSupport.validToken(user.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":-6,\"reason\":\"Merma\"}"))
                .andExpect(status().isOk());

        NotificationRequest sent = singleSentNotification();
        assertThat(sent.entityId()).isEqualTo(p);
        assertThat(sent.actorUserId()).isEqualTo(user);
        assertThat(sent.targetUserId()).isNull();
    }

    @Test
    void adjustmentsThatDoNotCrossDoNotNotify() throws Exception {
        UUID p = insertProduct("LS-10", 10, 5);
        String auth = "Bearer " + JwtTestSupport.validToken();

        mvc.perform(post("/api/products/" + p + "/stock-adjustments").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-2,\"reason\":\"a\"}"))
                .andExpect(status().isOk());   // 10 -> 8: sigue por encima
        mvc.perform(post("/api/products/" + p + "/stock-adjustments").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":20,\"reason\":\"b\"}"))
                .andExpect(status().isOk());   // 8 -> 28: positivo
        mvc.perform(post("/api/products/" + p + "/stock-adjustments").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-100,\"reason\":\"c\"}"))
                .andExpect(status().isConflict()); // rechazado: no hay aviso

        verify(notificationClient, never()).send(any());
    }

    @Test
    void anAdjustmentFromBelowTheThresholdDoesNotNotifyAgain() throws Exception {
        UUID p = insertProduct("LS-11", 4, 5);

        mvc.perform(post("/api/products/" + p + "/stock-adjustments").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-1,\"reason\":\"x\"}"))
                .andExpect(status().isOk());

        verify(notificationClient, never()).send(any());
    }

    @Test
    void aFailingNotificationClientNeverBreaksAnAdjustment() throws Exception {
        doThrow(new IllegalStateException("down")).when(notificationClient).send(any());
        UUID p = insertProduct("LS-12", 10, 5);

        mvc.perform(post("/api/products/" + p + "/stock-adjustments").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-9,\"reason\":\"x\"}"))
                .andExpect(status().isOk());

        assertThat(stockOf(p)).isEqualTo(1);
    }
}
