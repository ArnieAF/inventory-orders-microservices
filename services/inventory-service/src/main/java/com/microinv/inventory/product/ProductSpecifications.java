package com.microinv.inventory.product;

import com.microinv.inventory.common.Texts;
import org.springframework.data.jpa.domain.Specification;

/** Filtros dinamicos del listado. Solo se anade un predicado si el parametro viene informado. */
final class ProductSpecifications {

    private ProductSpecifications() {
    }

    static Specification<Product> active() {
        return (root, query, cb) -> cb.isTrue(root.get("active"));
    }

    /** SKU o nombre contienen el texto (sin distinguir mayusculas; los comodines del usuario son literales). */
    static Specification<Product> search(String term) {
        String pattern = Texts.containsPattern(term);
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("sku")), pattern, Texts.LIKE_ESCAPE),
                cb.like(cb.lower(root.get("name")), pattern, Texts.LIKE_ESCAPE));
    }

    static Specification<Product> lowStock() {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.<Integer>get("stock"),
                root.<Integer>get("lowStockThreshold"));
    }
}
