package com.aesoftwaresolutions.solid.search;

import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.org.LegalEntity;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** One query, every module that has something to say about it. */
@Service
public class SearchService {

    static final int LIMIT_PER_KIND = 10;
    private static final int MIN_QUERY_LENGTH = 2;

    private final List<SearchProvider> providers;
    private final OrgScope orgScope;
    private final OrgService orgs;

    SearchService(List<SearchProvider> providers, OrgScope orgScope, OrgService orgs) {
        this.providers = providers;
        this.orgScope = orgScope;
        this.orgs = orgs;
    }

    public SearchModels.Results search(UUID orgId, UUID entityId, String rawQuery) {
        LegalEntity entity = orgs.getEntity(orgId, entityId);
        String text = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        if (text.length() < MIN_QUERY_LENGTH) {
            // One letter would match most of the ledger, which is not an answer.
            return new SearchModels.Results(text, null, List.of());
        }
        Long amountMinor = amountOf(text);
        SearchModels.Query query = new SearchModels.Query(text, amountMinor, LIMIT_PER_KIND);

        List<SearchModels.Group> groups = new ArrayList<>();
        orgScope.run(orgId, () -> {
            for (SearchProvider provider : providers) {
                for (SearchProvider.Found found : provider.search(orgId, entityId, query)) {
                    if (found.total() > 0) {
                        List<SearchModels.Hit> hits = new ArrayList<>(found.hits());
                        hits.sort(Comparator.comparing(SearchModels.Hit::date,
                                Comparator.nullsLast(Comparator.reverseOrder())));
                        groups.add(new SearchModels.Group(found.kind(), found.total(), List.copyOf(hits)));
                    }
                }
            }
        });
        groups.sort(Comparator.comparing(SearchModels.Group::kind));
        Money interpreted = amountMinor == null ? null : Money.ofMinor(amountMinor, entity.baseCurrency());
        return new SearchModels.Results(text, interpreted, List.copyOf(groups));
    }

    /**
     * Reads the query as an amount when it looks like one: {@code 420}, {@code $420.00}, {@code 1,250.50},
     * {@code -420}. Returns the size in minor units, without a sign — a payment and a receipt of the same size
     * are equally likely to be what someone is looking for. Anything with more than two decimals, or any other
     * text, is not an amount.
     */
    static Long amountOf(String text) {
        String cleaned = text.replace("$", "").replace(",", "").replace(" ", "");
        if (!cleaned.matches("-?\\d+(\\.\\d{1,2})?")) {
            return null;
        }
        // BigDecimal, never a double: the query "0.1" must mean ten cents exactly.
        return new BigDecimal(cleaned).movePointRight(2).abs().longValueExact();
    }
}
