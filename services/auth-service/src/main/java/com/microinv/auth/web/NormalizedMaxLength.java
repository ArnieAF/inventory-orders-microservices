package com.microinv.auth.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Longitud maxima medida DESPUES de normalizar a minusculas (el valor que realmente se guarda).
 * toLowerCase puede alargar una cadena (p. ej. U+0130 pasa a dos caracteres), y entonces el valor
 * normalizado desbordaria la columna de la BD (error 22001 -> 500). Con el email limitado a ASCII
 * imprimible no deberia ocurrir, pero la comprobacion se mantiene como garantia independiente.
 */
@Documented
@Constraint(validatedBy = NormalizedMaxLengthValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface NormalizedMaxLength {

    String message() default "must be at most {value} characters after normalization";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    int value();
}
