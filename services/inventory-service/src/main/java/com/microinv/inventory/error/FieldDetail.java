package com.microinv.inventory.error;

/** Detalle de un error de validacion por campo. */
public record FieldDetail(String field, String message) {
}
