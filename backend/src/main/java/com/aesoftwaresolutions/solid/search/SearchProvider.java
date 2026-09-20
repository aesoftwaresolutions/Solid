package com.aesoftwaresolutions.solid.search;

import java.util.List;
import java.util.UUID;

/**
 * Implemented by each module that owns records worth finding. The search module calls every provider and puts
 * the answers together; it never touches another module's tables itself.
 *
 * <p>An implementation runs inside the caller's organization scope, must respect {@code limitPerKind}, and
 * reports the true match count in {@link Found#total} so the caller can say "10 of 43".
 */
public interface SearchProvider {

    /** @param total the real number of matches, even when fewer hits are returned */
    record Found(String kind, int total, List<SearchModels.Hit> hits) {
    }

    List<Found> search(UUID orgId, UUID entityId, SearchModels.Query query);
}
