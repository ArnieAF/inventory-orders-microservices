package com.microinv.auth.web;

import com.microinv.auth.user.Normalizer;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NormalizedMaxLengthValidator implements ConstraintValidator<NormalizedMaxLength, CharSequence> {

    private int max;

    @Override
    public void initialize(NormalizedMaxLength annotation) {
        this.max = annotation.value();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null || Normalizer.lower(value.toString()).length() <= max;
    }
}
