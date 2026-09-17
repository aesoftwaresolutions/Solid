package com.aesoftwaresolutions.solid.platform;

import java.util.Optional;
import java.util.UUID;

/**
 * Who is making the current request, available to any module (e.g. for audit logging) without depending on
 * the security framework. Set by the iam module's authentication filter; empty for background jobs and tests.
 */
public final class RequestContext {

    public record Caller(UUID userId, String ip) {
    }

    private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void set(Caller caller) {
        CURRENT.set(caller);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<Caller> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
