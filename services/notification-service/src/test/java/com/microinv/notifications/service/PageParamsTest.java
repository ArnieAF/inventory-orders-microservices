package com.microinv.notifications.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microinv.notifications.api.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class PageParamsTest {

    @Test
    void defaultsToNewestFirst() {
        Pageable pageable = PageParams.parse(0, 20, null);
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void sizeBoundariesAre1And100() {
        assertThat(PageParams.parse(0, 1, null).getPageSize()).isEqualTo(1);
        assertThat(PageParams.parse(0, 100, null).getPageSize()).isEqualTo(100);
        assertInvalid(() -> PageParams.parse(0, 0, null), "size");
        assertInvalid(() -> PageParams.parse(0, 101, null), "size");
        assertInvalid(() -> PageParams.parse(0, -5, null), "size");
        assertInvalid(() -> PageParams.parse(0, Integer.MAX_VALUE, null), "size");
    }

    @Test
    void negativePageIsRejected() {
        assertInvalid(() -> PageParams.parse(-1, 20, null), "page");
    }

    @Test
    void createdAtIsTheOnlySortableField() {
        assertThat(PageParams.parse(0, 20, "createdAt,asc").getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.ASC);
        assertThat(PageParams.parse(0, 20, "createdAt,DESC").getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(PageParams.parse(0, 20, "createdAt").getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "message,asc", "id,asc", "createdAt,sideways", "createdAt,asc,extra",
            "n.createdAt", "createdAt;drop table notifications", "createdat,asc", ",asc"})
    void anythingElseIsRejected(String sort) {
        assertInvalid(() -> PageParams.parse(0, 20, sort), "sort");
    }

    private static void assertInvalid(Runnable action, String field) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus().value()).isEqualTo(400);
            assertThat(ex.getCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(ex.getDetails()).extracting("field").containsExactly(field);
        });
    }
}
