package com.microinv.orders.service;

import com.microinv.orders.client.ReserveResult;
import com.microinv.orders.client.ReservedItem;
import com.microinv.orders.domain.OrderLine;
import com.microinv.orders.dto.ItemRequest;
import com.microinv.orders.error.ApiException;
import com.microinv.orders.error.InventoryUnavailableException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Logica pura de lineas de pedido: validacion de items repetidos y de la instantanea devuelta por inventario. */
final class OrderLines {

    static final BigDecimal MAX_UNIT_PRICE = new BigDecimal("999999.99");
    private static final int MAX_SKU_LENGTH = 40;
    private static final int MAX_NAME_LENGTH = 120;

    private OrderLines() {
    }

    /** Rechaza {@code productId} repetidos (400 VALIDATION_ERROR con el indice del repetido). */
    static void rejectDuplicates(List<ItemRequest> items) {
        Set<UUID> seen = new HashSet<>();
        List<Map<String, Object>> details = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!seen.add(items.get(i).productId())) {
                details.add(Map.of("field", "items[" + i + "].productId", "message", "must not be repeated"));
            }
        }
        if (!details.isEmpty()) {
            throw ApiException.validation("Validation failed", details);
        }
    }

    /**
     * Cruza lo pedido con lo que devolvio inventario. El precio unitario sale SIEMPRE de inventario.
     * Si la respuesta es incoherente (items que faltan, sobran, cantidades distintas, precio invalido...)
     * se lanza {@link InventoryUnavailableException}: el llamador compensa con {@code release}.
     */
    static List<OrderLine> fromReservation(List<ItemRequest> requested, ReserveResult reserved) {
        List<ReservedItem> returned = reserved == null ? null : reserved.items();
        if (returned == null || returned.size() != requested.size()) {
            throw invalidResponse();
        }
        Map<UUID, ReservedItem> byProduct = new HashMap<>();
        for (ReservedItem item : returned) {
            if (item == null || item.productId() == null || byProduct.put(item.productId(), item) != null) {
                throw invalidResponse();
            }
        }
        List<OrderLine> lines = new ArrayList<>();
        for (ItemRequest request : requested) {
            ReservedItem item = byProduct.get(request.productId());
            if (item == null || item.quantity() == null || !item.quantity().equals(request.quantity())) {
                throw invalidResponse();
            }
            lines.add(new OrderLine(request.productId(), requireText(item.sku(), MAX_SKU_LENGTH),
                    requireText(item.name(), MAX_NAME_LENGTH), requirePrice(item.unitPrice()),
                    request.quantity()));
        }
        return lines;
    }

    private static String requireText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw invalidResponse();
        }
        return value;
    }

    /** Precio entre 0 y 999999.99 con como maximo 2 decimales significativos; se normaliza a escala 2. */
    private static BigDecimal requirePrice(BigDecimal price) {
        if (price == null || price.signum() < 0 || price.compareTo(MAX_UNIT_PRICE) > 0
                || price.stripTrailingZeros().scale() > 2) {
            throw invalidResponse();
        }
        return price.setScale(2);
    }

    private static InventoryUnavailableException invalidResponse() {
        return new InventoryUnavailableException(false, null);
    }
}
