package com.aesoftwaresolutions.solid.docs;

import com.aesoftwaresolutions.solid.search.SearchModels;
import com.aesoftwaresolutions.solid.search.SearchProvider;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Finds documents by file name and note. The contents are not searched: the vault is encrypted, and quietly
 * decrypting every file to answer a search is not something this software does.
 */
@Component
class DocumentSearchProvider implements SearchProvider {

    private final JdbcClient db;

    DocumentSearchProvider(JdbcClient db) {
        this.db = db;
    }

    @Override
    public List<Found> search(UUID orgId, UUID entityId, SearchModels.Query query) {
        String like = "%" + query.text() + "%";
        String where = """
                from doc.document d
                where d.entity_id = ? and (lower(d.filename) like ? escape '\\' or lower(coalesce(d.note, '')) like ? escape '\\')""";
        int total = db.sql("select count(*) " + where).params(entityId, like, like).query(Integer.class).single();
        List<SearchModels.Hit> hits = db.sql("select d.id, d.filename, d.kind, d.uploaded_at " + where
                + " order by d.uploaded_at desc limit " + query.limitPerKind())
                .params(entityId, like, like)
                .query((rs, n) -> {
                    OffsetDateTime uploaded = rs.getObject("uploaded_at", OffsetDateTime.class);
                    LocalDate date = uploaded == null ? null : uploaded.toLocalDate();
                    return new SearchModels.Hit("document", rs.getObject("id", UUID.class),
                            rs.getString("filename"), rs.getString("kind"), date, null, "documents");
                })
                .list();
        return List.of(new Found("document", total, hits));
    }
}
