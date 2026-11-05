package com.microinv.inventory.product;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** El entero anotado no puede ser 0 (los nulos los valida {@code @NotNull}). */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotZero.Validator.class)
public @interface NotZero {

    String message() default "must not be zero";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NotZero, Integer> {
        @Override
        public boolean isValid(Integer value, ConstraintValidatorContext context) {
            return value == null || value != 0;
        }
    }
}
