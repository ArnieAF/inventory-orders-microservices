package com.microinv.inventory.product;

import com.microinv.inventory.common.Texts;
import com.microinv.inventory.error.ApiException;
import com.microinv.inventory.error.ApiException.StockShortage;
import com.microinv.inventory.stock.LowStockEvent;
import com.microinv.inventory.stock.LowStockPolicy;
import com.microinv.inventory.stock.StockMovement;
import com.microinv.inventory.stock.StockMovementRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de productos. Toda modificacion lee el producto CON BLOQUEO (SELECT ... FOR UPDATE) para
 * convivir con las reservas, que cambian el stock en paralelo. Un producto dado de baja se comporta como
 * inexistente para la API publica (404), pero sigue existiendo para liberar reservas.
 */
@Service
public class ProductService {

    static final int MAX_SEARCH_LENGTH = 100;

    private final ProductRepository products;
    private final ProductLocker locker;
    private final StockMovementRepository movements;
    private final ApplicationEventPublisher events;

    public ProductService(ProductRepository products, ProductLocker locker, StockMovementRepository movements,
                          ApplicationEventPublisher events) {
        this.products = products;
        this.locker = locker;
        this.movements = movements;
        this.events = events;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        String sku = Texts.normalizeSku(request.sku());
        if (products.existsBySku(sku)) {
            throw ApiException.skuAlreadyExists();
        }
        int threshold = request.lowStockThreshold() != null ? request.lowStockThreshold()
                : CreateProductRequest.DEFAULT_LOW_STOCK_THRESHOLD;
        Product product = Product.create(sku, request.name().trim(), Texts.blankToNull(request.description()),
                request.price(), request.stock(), threshold);
        try {
            products.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            // Carrera entre dos altas con el mismo SKU: gana la restriccion UNIQUE de la BD.
            throw ApiException.skuAlreadyExists();
        }
        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(String search, boolean lowStockOnly, Pageable pageable) {
        Specification<Product> spec = ProductSpecifications.active();
        String term = Texts.blankToNull(search);
        if (term != null) {
            if (term.length() > MAX_SEARCH_LENGTH) {
                throw ApiException.validation("search", "must be at most " + MAX_SEARCH_LENGTH + " characters");
            }
            spec = spec.and(ProductSpecifications.search(term));
        }
        if (lowStockOnly) {
            spec = spec.and(ProductSpecifications.lowStock());
        }
        return products.findAll(spec, pageable).map(ProductResponse::from);
    }

    @Transactional(readOnly = true)
    public ProductSummary summary() {
        return new ProductSummary(products.countByActiveTrue(), products.countLowStock(),
                products.sumActiveStock());
    }

    @Transactional(readOnly = true)
    public ProductResponse get(UUID id) {
        return products.findById(id).filter(Product::isActive).map(ProductResponse::from)
                .orElseThrow(() -> ApiException.notFound("Product not found"));
    }

    @Transactional
    public ProductResponse update(UUID id, UpdateProductRequest request) {
        Product product = lockActive(id);
        String sku = Texts.normalizeSku(request.sku());
        if (!sku.equals(product.getSku()) && products.existsBySkuAndIdNot(sku, id)) {
            throw ApiException.skuAlreadyExists();
        }
        product.updateDetails(sku, request.name().trim(), Texts.blankToNull(request.description()), request.price(),
                request.lowStockThreshold());
        try {
            products.flush();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.skuAlreadyExists();
        }
        return ProductResponse.from(product);
    }

    /** Baja logica: {@code active=false}. La fila y su SKU se conservan (los pedidos antiguos la referencian). */
    @Transactional
    public void delete(UUID id) {
        Product product = lockActive(id);
        product.deactivate();
        products.flush();
    }

    @Transactional
    public ProductResponse adjustStock(UUID id, StockAdjustmentRequest request, UUID actorUserId) {
        Product product = lockActive(id);
        int delta = request.delta();
        int before = product.getStock();
        long after = (long) before + delta;
        if (after < 0) {
            throw ApiException.insufficientStock(List.of(new StockShortage(id, -delta, before)));
        }
        if (after > Integer.MAX_VALUE) {
            throw ApiException.validation("delta", "resulting stock exceeds the maximum allowed");
        }
        product.changeStock((int) after);
        movements.save(StockMovement.adjustment(id, delta, request.reason().trim()));
        products.flush();
        if (LowStockPolicy.crossedBelowThreshold(before, product.getStock(), product.getLowStockThreshold())) {
            events.publishEvent(new LowStockEvent(product.getId(), product.getSku(), product.getName(),
                    product.getStock(), product.getLowStockThreshold(), actorUserId));
        }
        return ProductResponse.from(product);
    }

    private Product lockActive(UUID id) {
        return locker.lock(id).filter(Product::isActive)
                .orElseThrow(() -> ApiException.notFound("Product not found"));
    }
}
