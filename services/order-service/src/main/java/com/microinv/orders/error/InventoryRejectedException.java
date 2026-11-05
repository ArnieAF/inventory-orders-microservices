package com.microinv.orders.error;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Rechazo definitivo de inventario (409 INSUFFICIENT_STOCK / 409 PRODUCT_INACTIVE / 404 NOT_FOUND).
 * Se propaga al cliente con el mismo codigo y los mismos {@code details}.
 */
public class InventoryRejectedException extends ApiException {

    public InventoryRejectedException(HttpStatus status, ErrorCode code, String message,
                                      List<Map<String, Object>> details) {
        super(status, code, message, details, null);
    }
}
