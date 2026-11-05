package com.microinv.inventory.common;

import com.microinv.inventory.error.ApiException;
import com.microinv.inventory.error.ErrorCode;
import com.microinv.inventory.error.FieldDetail;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Convierte {@code page,size,sort} en un {@link Pageable} validado (contrato 5): {@code size} en 1..100 y
 * campos de orden SOLO de una lista blanca (si no, 400). Los nombres de la API se traducen a propiedades
 * de la entidad, de modo que nada del cliente llega a la consulta sin pasar por la lista blanca.
 */
public final class PageRequests {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final int MAX_SORT_FIELDS = 3;

    private PageRequests() {
    }

    /**
     * @param sortFields lista blanca: nombre en la API a propiedad de la entidad
     * @param defaultSort orden si el cliente no envia {@code sort}
     * @param tieBreaker propiedad unica anadida al final para que la paginacion sea estable
     */
    public static Pageable parse(int page, int size, List<String> sortParams, Map<String, String> sortFields,
                                 Sort defaultSort, String tieBreaker) {
        List<FieldDetail> errors = new ArrayList<>();
        if (page < 0) {
            errors.add(new FieldDetail("page", "must be greater than or equal to 0"));
        }
        if (size < 1 || size > MAX_SIZE) {
            errors.add(new FieldDetail("size", "must be between 1 and " + MAX_SIZE));
        }
        Sort sort = parseSort(sortParams, sortFields, defaultSort, errors);
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Validation failed", errors);
        }
        return PageRequest.of(page, size, sort.and(Sort.by(Sort.Direction.ASC, tieBreaker)));
    }

    private static Sort parseSort(List<String> sortParams, Map<String, String> sortFields, Sort defaultSort,
                                  List<FieldDetail> errors) {
        List<String> effective = sortParams == null ? List.of()
                : sortParams.stream().filter(s -> s != null && !s.isBlank()).toList();
        if (effective.isEmpty()) {
            return defaultSort;
        }
        if (effective.size() > MAX_SORT_FIELDS) {
            errors.add(new FieldDetail("sort", "at most " + MAX_SORT_FIELDS + " sort fields are allowed"));
            return defaultSort;
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String raw : effective) {
            String[] parts = raw.split(",", -1);
            if (parts.length > 2) {
                errors.add(new FieldDetail("sort", "expected format field,asc|desc"));
                continue;
            }
            String property = sortFields.get(parts[0].trim());
            if (property == null) {
                errors.add(new FieldDetail("sort", "unsupported sort field"));
                continue;
            }
            Sort.Direction direction = Sort.Direction.ASC;
            if (parts.length == 2) {
                String dir = parts[1].trim();
                if (dir.equalsIgnoreCase("asc")) {
                    direction = Sort.Direction.ASC;
                } else if (dir.equalsIgnoreCase("desc")) {
                    direction = Sort.Direction.DESC;
                } else {
                    errors.add(new FieldDetail("sort", "direction must be asc or desc"));
                    continue;
                }
            }
            orders.add(new Sort.Order(direction, property));
        }
        return orders.isEmpty() ? defaultSort : Sort.by(orders);
    }
}
