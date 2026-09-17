package com.aesoftwaresolutions.solid.iam;

import java.util.UUID;

/** The authenticated caller attached to a request. */
public record SolidPrincipal(UUID userId, UUID sessionId, String email, boolean mfaVerified, boolean bearer) {
}
