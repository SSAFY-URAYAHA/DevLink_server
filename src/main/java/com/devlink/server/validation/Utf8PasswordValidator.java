package com.devlink.server.validation;

import java.nio.charset.StandardCharsets;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class Utf8PasswordValidator implements ConstraintValidator<Utf8Password, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
