package com.microinv.notifications.api.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** Paginación común (§5): {@code { content, page, size, totalElements, totalPages }}. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }
}
