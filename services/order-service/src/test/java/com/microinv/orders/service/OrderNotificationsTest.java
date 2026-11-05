package com.microinv.orders.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.microinv.orders.client.NotificationRequest;
import com.microinv.orders.client.NotificationType;
import com.microinv.orders.domain.OrderStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderNotificationsTest {

    private static final UUID ORDER = UUID.fromString("abcdef12-3456-4789-8abc-def123456789");
    private static final UUID CREATOR = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTOR = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void newOrderIsABroadcastFromTheCreator() {
        NotificationRequest request = OrderNotifications.newOrder(new OrderCreatedEvent(ORDER, CREATOR));

        assertThat(request.type()).isEqualTo(NotificationType.NEW_ORDER);
        assertThat(request.targetUserId()).isNull();
        assertThat(request.actorUserId()).isEqualTo(CREATOR);
        assertThat(request.entityType()).isEqualTo("ORDER");
        assertThat(request.entityId()).isEqualTo(ORDER);
        assertThat(request.title()).isEqualTo("Nuevo pedido");
        assertThat(request.message()).isEqualTo("Se creó el pedido #ABCDEF12");
    }

    @Test
    void statusChangeTargetsTheOrderCreatorAndNamesTheActor() {
        NotificationRequest request = OrderNotifications.statusChanged(
                new OrderStatusChangedEvent(ORDER, CREATOR, ACTOR, OrderStatus.PENDING, OrderStatus.CONFIRMED));

        assertThat(request.type()).isEqualTo(NotificationType.ORDER_STATUS_CHANGED);
        assertThat(request.targetUserId()).isEqualTo(CREATOR);
        assertThat(request.actorUserId()).isEqualTo(ACTOR);
        assertThat(request.entityType()).isEqualTo("ORDER");
        assertThat(request.entityId()).isEqualTo(ORDER);
        assertThat(request.title()).isEqualTo("Estado del pedido actualizado");
        assertThat(request.message()).isEqualTo("El pedido #ABCDEF12 pasó de PENDING a CONFIRMED");
    }

    @Test
    void textsRespectTheContractLimits() {
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                NotificationRequest request = OrderNotifications.statusChanged(
                        new OrderStatusChangedEvent(ORDER, CREATOR, ACTOR, from, to));
                assertThat(request.title().length()).isBetween(1, 120);
                assertThat(request.message().length()).isBetween(1, 500);
            }
        }
    }
}
