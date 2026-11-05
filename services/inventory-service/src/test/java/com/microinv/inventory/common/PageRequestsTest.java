package com.microinv.inventory.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microinv.inventory.error.ApiException;
import com.microinv.inventory.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class PageRequestsTest {

    private static final Map<String, String> FIELDS = Map.of("name", "name", "createdAt", "createdAt",
            "price", "price");
    private static final Sort DEFAULT = Sort.by(Sort.Direction.DESC, "createdAt");

    private static Pageable parse(int page, int size, String... sort) {
        return PageRequests.parse(page, size, List.of(sort), FIELDS, DEFAULT, "id");
    }

    @Test
    void usesDefaultSortAndAddsStableTieBreaker() {
        Pageable p = parse(0, 20);
        assertThat(p.getPageNumber()).isZero();
        assertThat(p.getPageSize()).isEqualTo(20);
        assertThat(p.getSort().toList()).extracting(Sort.Order::getProperty).containsExactly("createdAt", "id");
        assertThat(p.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void parsesFieldAndDirection() {
        Pageable p = parse(2, 50, "price,desc", "name");
        assertThat(p.getPageNumber()).isEqualTo(2);
        assertThat(p.getSort().getOrderFor("price").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(p.getSort().getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(p.getSort().getOrderFor("id")).isNotNull();
    }

    @Test
    void directionIsCaseInsensitive() {
        assertThat(parse(0, 10, "name,DESC").getSort().getOrderFor("name").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void acceptsMaxSizeAndBlankSortIsIgnored() {
        assertThat(parse(0, 100, "").getPageSize()).isEqualTo(100);
    }

    @Test
    void rejectsSizeOutOfRange() {
        assertValidationError(() -> parse(0, 101));
        assertValidationError(() -> parse(0, 0));
        assertValidationError(() -> parse(0, -5));
    }

    @Test
    void rejectsNegativePage() {
        assertValidationError(() -> parse(-1, 20));
    }

    @Test
    void rejectsSortFieldsOutsideWhitelist() {
        assertValidationError(() -> parse(0, 20, "password"));
        assertValidationError(() -> parse(0, 20, "id"));
        assertValidationError(() -> parse(0, 20, "name;drop table products"));
        assertValidationError(() -> parse(0, 20, "Name")); // distingue mayusculas: solo nombres exactos
        assertValidationError(() -> parse(0, 20, "name,asc", "version,desc"));
    }

    @Test
    void rejectsBadDirectionAndMalformedSort() {
        assertValidationError(() -> parse(0, 20, "name,sideways"));
        assertValidationError(() -> parse(0, 20, "name,asc,price"));
        assertValidationError(() -> parse(0, 20, ",asc"));
    }

    @Test
    void rejectsTooManySortFields() {
        assertValidationError(() -> parse(0, 20, "name", "price", "createdAt", "name"));
    }

    private static void assertValidationError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
