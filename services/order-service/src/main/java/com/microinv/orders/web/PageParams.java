package com.microinv.orders.web;

import com.microinv.orders.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Convierte {@code page,size,sort} de la query en un {@link Pageable} seguro (contrato, seccion 5):
 * {@code size} entre 1 y 100 y campos de orden SOLO de una lista blanca (si no, 400 VALIDATION_ERROR).
 * Nunca se pasa texto del cliente al ORDER BY sin pasar por la lista blanca.
 */
public final class PageParams {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    private static final int MAX_SORT_FIELDS = 3;
    /** Desempate estable para que la paginacion no repita ni pierda filas. */
    private static final String TIE_BREAKER = "id";

    private PageParams() {
    }

    /**
     * @param page         pagina (base 0); {@code null} = 0
     * @param size         tamano; {@code null} = 20
     * @param sort         valores {@code campo[,asc|desc]} (puede venir repetido); {@code null}/vacio = por defecto
     * @param allowed      campos de orden permitidos (nombres de atributo de la entidad)
     * @param defaultSort  orden por defecto
     */
    public static Pageable of(Integer page, Integer size, List<String> sort, Set<String> allowed,
                              Sort defaultSort) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_SIZE : size;
        if (pageNumber < 0) {
            throw ApiException.validation("page", "must be greater than or equal to 0");
        }
        if (pageSize < 1 || pageSize > MAX_SIZE) {
            throw ApiException.validation("size", "must be between 1 and " + MAX_SIZE);
        }
        return PageRequest.of(pageNumber, pageSize, parseSort(sort, allowed, defaultSort));
    }

    static Sort parseSort(List<String> sort, Set<String> allowed, Sort defaultSort) {
        List<String> values = sort == null ? List.of() : sort.stream().filter(s -> s != null && !s.isBlank()).toList();
        if (values.isEmpty()) {
            return defaultSort.and(Sort.by(Sort.Direction.ASC, TIE_BREAKER));
        }
        if (values.size() > MAX_SORT_FIELDS) {
            throw ApiException.validation("sort", "at most " + MAX_SORT_FIELDS + " sort fields are allowed");
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String value : values) {
            String[] parts = value.split(",", -1);
            if (parts.length > 2) {
                throw invalidSort(allowed);
            }
            String field = parts[0].trim();
            if (!allowed.contains(field)) {
                throw invalidSort(allowed);
            }
            Sort.Direction direction = Sort.Direction.ASC;
            if (parts.length == 2) {
                String dir = parts[1].trim();
                if ("desc".equalsIgnoreCase(dir)) {
                    direction = Sort.Direction.DESC;
                } else if (!"asc".equalsIgnoreCase(dir)) {
                    throw invalidSort(allowed);
                }
            }
            orders.add(new Sort.Order(direction, field));
        }
        orders.add(Sort.Order.asc(TIE_BREAKER));
        return Sort.by(orders);
    }

    private static ApiException invalidSort(Set<String> allowed) {
        return ApiException.validation("sort",
                "must be one of " + String.join(", ", allowed.stream().sorted().toList())
                        + " optionally followed by ,asc or ,desc");
    }
}
