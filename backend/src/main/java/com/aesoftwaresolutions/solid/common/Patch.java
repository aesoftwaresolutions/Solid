package com.aesoftwaresolutions.solid.common;

import java.util.Map;
import java.util.UUID;

/**
 * Reads one field out of a PATCH body.
 *
 * <p>A PATCH has three cases and they are not the same: the field is absent (leave it alone), the field is null
 * (clear it), or the field has a value (set it). {@link java.util.Optional} cannot hold a null, so it cannot tell
 * "clear" from "leave alone" — hence {@link Field}.
 */
public final class Patch {

    private Patch() {
    }

    /** A field that was sent ({@code present}) with a value that may be null, or was not sent at all. */
    public record Field<T>(boolean present, T value) {

        public static <T> Field<T> absent() {
            return new Field<>(false, null);
        }

        public static <T> Field<T> of(T value) {
            return new Field<>(true, value);
        }

        /** True when the caller sent a real (non-null) value. */
        public boolean hasValue() {
            return present && value != null;
        }
    }

    public static Field<String> text(Map<String, Object> body, String field, int maxLength) {
        if (!body.containsKey(field)) {
            return Field.absent();
        }
        Object value = body.get(field);
        if (value == null) {
            return Field.of(null);
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + " must be text");
        }
        if (text.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return Field.of(text);
    }

    public static Field<Boolean> flag(Map<String, Object> body, String field) {
        if (!body.containsKey(field)) {
            return Field.absent();
        }
        if (!(body.get(field) instanceof Boolean flag)) {
            throw new IllegalArgumentException(field + " must be true or false");
        }
        return Field.of(flag);
    }

    public static Field<UUID> id(Map<String, Object> body, String field) {
        if (!body.containsKey(field)) {
            return Field.absent();
        }
        Object value = body.get(field);
        if (value == null) {
            return Field.of(null);
        }
        try {
            return Field.of(UUID.fromString(String.valueOf(value)));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " must be an id");
        }
    }
}
