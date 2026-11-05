package com.microinv.inventory.error;

import java.util.List;
import java.util.UUID;

/** Error de negocio con codigo del contrato. El mensaje es breve, generico y sin detalles internos. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient List<?> details;

    public ApiException(ErrorCode code, String message, List<?> details) {
        super(message, null, false, false);
        this.code = code;
        this.details = details;
    }

    public ErrorCode getCode() {
        return code;
    }

    public List<?> getDetails() {
        return details;
    }

    public static ApiException notFound(String message) {
        return new ApiException(ErrorCode.NOT_FOUND, message, null);
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "Validation failed",
                List.of(new FieldDetail(field, message)));
    }

    public static ApiException skuAlreadyExists() {
        return new ApiException(ErrorCode.SKU_ALREADY_EXISTS, "SKU already exists", null);
    }

    /** {@code NOT_FOUND} de reserve: {@code details:[{productId, message}]} con todos los productos inexistentes. */
    public static ApiException productsNotFound(List<UUID> productIds) {
        return new ApiException(ErrorCode.NOT_FOUND, "Product not found",
                productIds.stream().map(id -> new ProductDetail(id, "Product not found")).toList());
    }

    /** {@code PRODUCT_INACTIVE} de reserve: {@code details:[{productId, message}]} con todos los inactivos. */
    public static ApiException productsInactive(List<UUID> productIds) {
        return new ApiException(ErrorCode.PRODUCT_INACTIVE, "Product is inactive",
                productIds.stream().map(id -> new ProductDetail(id, "Product is inactive")).toList());
    }

    public static ApiException insufficientStock(List<StockShortage> shortages) {
        return new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Insufficient stock", shortages);
    }

    public static ApiException invalidStatusTransition(String message) {
        return new ApiException(ErrorCode.INVALID_STATUS_TRANSITION, message, null);
    }

    /** Detalle de {@code NOT_FOUND} y {@code PRODUCT_INACTIVE} en reserve: {@code {productId, message}}. */
    public record ProductDetail(UUID productId, String message) {
    }

    /** Detalle de {@code INSUFFICIENT_STOCK}: {@code {productId, requested, available}}. */
    public record StockShortage(UUID productId, int requested, int available) {
    }
}
