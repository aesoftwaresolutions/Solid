package com.aesoftwaresolutions.solid.org;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Organization(UUID id, String name, String kind, OffsetDateTime createdAt) {
}
