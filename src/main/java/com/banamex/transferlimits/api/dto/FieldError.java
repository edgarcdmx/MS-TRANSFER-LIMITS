package com.banamex.transferlimits.api.dto;

/**
 * {@code FieldError} schema: a validation error of one request field.
 *
 * @param field path of the invalid field, e.g. {@code amount} or {@code accountId}
 * @param message description of the violated rule
 */
public record FieldError(String field, String message) {}
