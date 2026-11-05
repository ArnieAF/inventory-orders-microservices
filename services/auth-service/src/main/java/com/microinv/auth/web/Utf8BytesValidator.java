package com.microinv.auth.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

public class Utf8BytesValidator implements ConstraintValidator<Utf8Bytes, CharSequence> {

    private int min;
    private int max;

    @Override
    public void initialize(Utf8Bytes annotation) {
        this.min = annotation.min();
        this.max = annotation.max();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // la presencia la exige @NotNull / @NotEmpty
        }
        int bytes = value.toString().getBytes(StandardCharsets.UTF_8).length;
        return bytes >= min && bytes <= max;
    }
}
