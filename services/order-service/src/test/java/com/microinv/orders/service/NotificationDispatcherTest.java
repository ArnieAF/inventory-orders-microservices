package com.microinv.orders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.microinv.orders.client.NotificationClient;
import com.microinv.orders.client.NotificationRequest;
import com.microinv.orders.client.NotificationType;
import com.microinv.orders.domain.OrderStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationDispatcherTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final NotificationClient client = mock(NotificationClient.class);

    @Test
    void sendsNewOrderAndStatusChangeNotifications() {
        NotificationDispatcher dispatcher = new NotificationDispatcher(client, Runnable::run);

        dispatcher.onOrderCreated(new OrderCreatedEvent(ORDER, USER));
        dispatcher.onOrderStatusChanged(
                new OrderStatusChangedEvent(ORDER, USER, UUID.randomUUID(), OrderStatus.PENDING, OrderStatus.SHIPPED));

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(client, org.mockito.Mockito.times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(NotificationRequest::type)
                .containsExactly(NotificationType.NEW_ORDER, NotificationType.ORDER_STATUS_CHANGED);
    }

    @Test
    void aFailingClientNeverPropagatesAnException() {
        doThrow(new IllegalStateException("boom")).when(client).send(any());
        NotificationDispatcher dispatcher = new NotificationDispatcher(client, Runnable::run);

        assertThatCode(() -> dispatcher.onOrderCreated(new OrderCreatedEvent(ORDER, USER))).doesNotThrowAnyException();
        assertThatCode(() -> dispatcher.onOrderStatusChanged(
                new OrderStatusChangedEvent(ORDER, USER, USER, OrderStatus.PENDING, OrderStatus.CONFIRMED)))
                .doesNotThrowAnyException();
    }

    @Test
    void aRejectingExecutorNeverPropagatesAnException() {
        Executor full = task -> {
            throw new RejectedExecutionException("queue full");
        };
        NotificationDispatcher dispatcher = new NotificationDispatcher(client, full);

        assertThatCode(() -> dispatcher.onOrderCreated(new OrderCreatedEvent(ORDER, USER))).doesNotThrowAnyException();
    }

    @Test
    void sendingHappensThroughTheExecutorNotInTheCallingThread() {
        List<Runnable> queued = new ArrayList<>();
        NotificationDispatcher dispatcher = new NotificationDispatcher(client, queued::add);

        dispatcher.onOrderCreated(new OrderCreatedEvent(ORDER, USER));

        assertThat(queued).hasSize(1);
        org.mockito.Mockito.verifyNoInteractions(client); // aun no se ha enviado: no bloquea la respuesta
        queued.get(0).run();
        verify(client).send(any());
    }
}
