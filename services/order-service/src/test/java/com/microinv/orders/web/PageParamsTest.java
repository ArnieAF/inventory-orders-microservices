package com.microinv.orders.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microinv.orders.error.ApiException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class PageParamsTest {

    private static final Set<String> ALLOWED = OrderController.SORTABLE;
    private static final Sort DEFAULT = Sort.by(Sort.Direction.DESC, "createdAt");

    private static Pageable of(Integer page, Integer size, String... sort) {
        return PageParams.of(page, size, sort.length == 0 ? null : List.of(sort), ALLOWED, DEFAULT);
    }

    @Test
    void defaultsAre_page0_size20_newestFirst_withStableTieBreaker() {
        Pageable pageable = of(null, null);

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort().toList()).extracting(o -> o.getProperty() + ":" + o.getDirection())
                .containsExactly("createdAt:DESC", "id:ASC");
    }

    @Test
    void whitelistIsExactlyTheContractOne() {
        assertThat(ALLOWED).containsExactlyInAnyOrder("createdAt", "total", "status", "customerName");
    }

    @Test
    void acceptsEveryWhitelistedFieldInBothDirections() {
        for (String field : ALLOWED) {
            assertThat(of(0, 10, field).getSort().getOrderFor(field).getDirection()).isEqualTo(Sort.Direction.ASC);
            assertThat(of(0, 10, field + ",asc").getSort().getOrderFor(field).getDirection())
                    .isEqualTo(Sort.Direction.ASC);
            assertThat(of(0, 10, field + ",DESC").getSort().getOrderFor(field).getDirection())
                    .isEqualTo(Sort.Direction.DESC);
        }
    }

    @Test
    void sizeBoundariesAre1And100() {
        assertThat(of(0, 1).getPageSize()).isEqualTo(1);
        assertThat(of(0, 100).getPageSize()).isEqualTo(100);
        assertThatThrownBy(() -> of(0, 0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> of(0, 101)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> of(0, -5)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> of(-1, 10)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "id", "items", "items.sku", "createdBy", "createdAt,sideways",
            "createdAt,asc,more", "total;drop table orders", "TOTAL", "", ",desc", "created_at"})
    void anythingOutsideTheWhitelistIsAValidationError(String sort) {
        if (sort.isEmpty()) {
            // un valor vacio equivale a "sin sort" (orden por defecto)
            assertThat(of(0, 10, sort).getSort().isSorted()).isTrue();
            return;
        }
        assertThatThrownBy(() -> of(0, 10, sort)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status().value()).isEqualTo(400);
            assertThat(e.code().name()).isEqualTo("VALIDATION_ERROR");
            assertThat(e.details().get(0)).containsEntry("field", "sort");
        });
    }

    @Test
    void severalSortValuesAreAllCheckedAndCapped() {
        Pageable ok = of(0, 10, "status,asc", "total,desc");
        assertThat(ok.getSort().toList()).extracting(o -> o.getProperty())
                .containsExactly("status", "total", "id");

        assertThatThrownBy(() -> of(0, 10, "status", "password")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> of(0, 10, "status", "total", "createdAt", "customerName"))
                .isInstanceOf(ApiException.class);
    }
}
