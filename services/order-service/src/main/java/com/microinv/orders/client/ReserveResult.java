package com.microinv.orders.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Respuesta 200 de {@code POST /internal/stock/reserve}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReserveResult(List<ReservedItem> items) {
}
