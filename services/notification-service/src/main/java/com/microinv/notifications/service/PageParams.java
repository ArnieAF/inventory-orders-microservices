package com.microinv.notifications.service;

import com.microinv.notifications.api.ApiException;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Validación de paginación y orden según §5: {@code size} ≤ 100 y solo campos de orden de una lista blanca. */
public final class PageParams {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    /** Único campo de orden permitido para el historial de notificaciones. */
    public static final String SORT_FIELD = "createdAt";

    private PageParams() {
    }

    /**
     * @param sort valor del parámetro {@code sort} ({@code createdAt}, {@code createdAt,asc} o
     *             {@code createdAt,desc}); {@code null} o vacío = más recientes primero
     * @throws ApiException 400 {@code VALIDATION_ERROR} si algo no es válido
     */
    public static Pageable parse(int page, int size, String sort) {
        if (page < 0) {
            throw ApiException.invalid("page", "must be greater than or equal to 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw ApiException.invalid("size", "must be between 1 and " + MAX_SIZE);
        }
        Sort.Direction direction = parseDirection(sort);
        // El identificador desempata para que la paginación sea estable con la misma marca de tiempo.
        return PageRequest.of(page, size, Sort.by(new Sort.Order(direction, SORT_FIELD), new Sort.Order(direction, "id")));
    }

    private static Sort.Direction parseDirection(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.Direction.DESC;
        }
        String[] parts = sort.split(",", -1);
        if (parts.length > 2 || !SORT_FIELD.equals(parts[0].trim())) {
            throw ApiException.invalid("sort", "only '" + SORT_FIELD + "' is allowed");
        }
        if (parts.length == 1 || parts[1].isBlank()) {
            return Sort.Direction.DESC;
        }
        String direction = parts[1].trim().toLowerCase(Locale.ROOT);
        return switch (direction) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw ApiException.invalid("sort", "direction must be 'asc' or 'desc'");
        };
    }
}
