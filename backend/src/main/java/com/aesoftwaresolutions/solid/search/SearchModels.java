package com.aesoftwaresolutions.solid.search;

import com.aesoftwaresolutions.solid.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What a search asks for, and what it finds. */
public final class SearchModels {

    private SearchModels() {
    }

    /**
     * @param text         the text to look for, already trimmed and lower-cased
     * @param amountMinor  the same text read as an amount in minor units, or null — always positive, because
     *                     a payment and a receipt of the same size are equally likely to be what is wanted
     * @param limitPerKind how many hits one provider may return
     */
    public record Query(String text, Long amountMinor, int limitPerKind) {
    }

    /**
     * @param where the screen that shows this record, so the result can be turned into a link
     */
    public record Hit(String kind, UUID id, String label, String detail, LocalDate date, Money amount,
                      String where) {
    }

    /** @param total how many matched, which may be more than the hits returned */
    public record Group(String kind, int total, List<Hit> hits) {
    }

    public record Results(String query, Money amountInterpreted, List<Group> groups) {
    }
}
