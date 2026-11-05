package com.microinv.orders.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** Paginacion del contrato (seccion 5). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }
}
