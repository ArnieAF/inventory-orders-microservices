package com.microinv.orders.dto;

import com.microinv.orders.domain.OrderItemEntity;
import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
        UUID id,
        UUID productId,
        String sku,
        String productName,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal) {

    public static OrderItemResponse from(OrderItemEntity item) {
        return new OrderItemResponse(item.getId(), item.getProductId(), item.getSku(), item.getProductName(),
                item.getUnitPrice(), item.getQuantity(), item.getLineTotal());
    }
}
