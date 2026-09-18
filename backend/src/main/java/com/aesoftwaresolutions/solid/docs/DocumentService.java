package com.aesoftwaresolutions.solid.docs;

import com.aesoftwaresolutions.solid.audit.AuditLog;
import com.aesoftwaresolutions.solid.common.ApiProblemException;
import com.aesoftwaresolutions.solid.common.BusinessRuleException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import com.aesoftwaresolutions.solid.org.OrgService;
import com.aesoftwaresolutions.solid.platform.OrgScope;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** The document vault: receipts and paperwork, encrypted on disk and attached to the records they support. */
@Service
public class DocumentService {

    /** 25 MB. A phone photo of a receipt is well under 10 MB, so this is generous. */
    public static final long MAX_FILE_BYTES = 25L * 1024 * 1024;

    static final Set<String> KINDS = Set.of("receipt", "bank_statement", "w2", "form_1099", "invoice", "bill",
            "contract", "other");
    static final Set<String> OBJECT_TYPES = Set.of("journal_entry", "bank_transaction", "invoice", "bill", "asset");

    private final JdbcClient db;
    private final OrgScope orgScope;
    private final OrgService orgs;
    private final DocumentStore store;
    private final AuditLog audit;
    private final Clock clock;

    DocumentService(JdbcClient db, OrgScope orgScope, OrgService orgs, DocumentStore store, AuditLog audit, Clock clock) {
        this.db = db;
        this.orgScope = orgScope;
        this.orgs = orgs;
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    public DocumentModels.Document upload(UUID orgId, UUID entityId, String filename, String kind, String note,
                                          byte[] bytes) {
        orgs.getEntity(orgId, entityId);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("The file is empty");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new ApiProblemException(400, "FILE_TOO_LARGE", "Files must be 25 MB or smaller");
        }
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("Unknown document kind: " + kind);
        }
        String contentType = FileTypes.detect(bytes).orElseThrow(() -> new ApiProblemException(400,
                "UNSUPPORTED_FILE_TYPE", "That file type is not accepted (PDF, images, text, CSV or OFX only)"));
        String name = cleanFilename(filename);
        String sha256 = sha256(bytes);

        Optional<UUID> existing = orgScope.call(orgId, () ->
                db.sql("select id from doc.document where entity_id = ? and sha256 = ?")
                        .params(entityId, sha256).query(UUID.class).optional());
        if (existing.isPresent()) {
            return get(orgId, entityId, existing.get());
        }

        UUID id = Ids.newId();
        UUID uploadedBy = AuditLog.Actor.current().userId();
        String storageKey = store.store(orgId, id, LocalDate.now(clock), bytes);
        try {
            orgScope.run(orgId, () -> db.sql("""
                    insert into doc.document (id, org_id, entity_id, filename, content_type, size_bytes, sha256,
                                              storage_key, kind, note, uploaded_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, orgId, entityId, name, contentType, (long) bytes.length, sha256, storageKey, kind,
                            note, uploadedBy)
                    .update());
        } catch (RuntimeException e) {
            store.delete(storageKey);
            throw e;
        }
        audit.record(AuditLog.Actor.current(), orgId, "document_uploaded", "document", id,
                Map.of("kind", kind, "contentType", contentType, "sizeBytes", bytes.length, "sha256", sha256));
        return get(orgId, entityId, id);
    }

    public List<DocumentModels.Document> list(UUID orgId, UUID entityId, String kind, String linkedType,
                                              UUID linkedId) {
        orgs.getEntity(orgId, entityId);
        if (linkedType != null && !OBJECT_TYPES.contains(linkedType)) {
            throw new IllegalArgumentException("Unknown linked object type: " + linkedType);
        }
        return orgScope.call(orgId, () -> {
            List<UUID> ids = db.sql("""
                    select d.id from doc.document d
                    where d.entity_id = ?
                      and (cast(? as text) is null or d.kind = ?)
                      and (cast(? as text) is null or exists (
                            select 1 from doc.document_link l
                            where l.document_id = d.id and l.object_type = ? and l.object_id = cast(? as uuid)))
                    order by d.uploaded_at desc""")
                    .params(entityId, kind, kind, linkedType, linkedType,
                            linkedId == null ? null : linkedId.toString())
                    .query(UUID.class).list();
            List<DocumentModels.Document> documents = new ArrayList<>(ids.size());
            for (UUID id : ids) {
                documents.add(load(entityId, id));
            }
            return documents;
        });
    }

    public DocumentModels.Document get(UUID orgId, UUID entityId, UUID documentId) {
        orgs.getEntity(orgId, entityId);
        return orgScope.call(orgId, () -> load(entityId, documentId));
    }

    public DocumentModels.Content content(UUID orgId, UUID entityId, UUID documentId) {
        DocumentModels.Document document = get(orgId, entityId, documentId);
        String storageKey = orgScope.call(orgId, () ->
                db.sql("select storage_key from doc.document where entity_id = ? and id = ?")
                        .params(entityId, documentId).query(String.class).single());
        byte[] bytes = store.read(storageKey);
        audit.record(AuditLog.Actor.current(), orgId, "document_downloaded", "document", documentId,
                Map.of("kind", document.kind(), "sizeBytes", document.sizeBytes()));
        return new DocumentModels.Content(document.filename(), document.contentType(), bytes);
    }

    public DocumentModels.Document link(UUID orgId, UUID entityId, UUID documentId, String objectType, UUID objectId) {
        if (!OBJECT_TYPES.contains(objectType)) {
            throw new IllegalArgumentException("Unknown linked object type: " + objectType);
        }
        get(orgId, entityId, documentId);
        return orgScope.call(orgId, () -> {
            db.sql("""
                    insert into doc.document_link (document_id, object_type, object_id, org_id)
                    values (?, ?, ?, ?) on conflict do nothing""")
                    .params(documentId, objectType, objectId, orgId).update();
            return load(entityId, documentId);
        });
    }

    public DocumentModels.Document unlink(UUID orgId, UUID entityId, UUID documentId, String objectType, UUID objectId) {
        get(orgId, entityId, documentId);
        return orgScope.call(orgId, () -> {
            db.sql("delete from doc.document_link where document_id = ? and object_type = ? and object_id = ?")
                    .params(documentId, objectType, objectId).update();
            return load(entityId, documentId);
        });
    }

    public void delete(UUID orgId, UUID entityId, UUID documentId) {
        DocumentModels.Document document = get(orgId, entityId, documentId);
        if (!document.links().isEmpty()) {
            throw new BusinessRuleException("DOCUMENT_LINKED",
                    "Unlink this document from its " + document.links().size() + " record(s) before deleting it");
        }
        String storageKey = orgScope.call(orgId, () -> {
            String key = db.sql("select storage_key from doc.document where entity_id = ? and id = ?")
                    .params(entityId, documentId).query(String.class).single();
            db.sql("delete from doc.document where entity_id = ? and id = ?").params(entityId, documentId).update();
            return key;
        });
        store.delete(storageKey);
        audit.record(AuditLog.Actor.current(), orgId, "document_deleted", "document", documentId,
                Map.of("kind", document.kind()));
    }

    /** Must run inside {@link OrgScope}. */
    private DocumentModels.Document load(UUID entityId, UUID documentId) {
        Map<String, Object> row = db.sql("""
                select id, entity_id, filename, content_type, size_bytes, sha256, kind, note, uploaded_at, uploaded_by
                from doc.document where entity_id = ? and id = ?""")
                .params(entityId, documentId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Document " + documentId + " not found"));
        List<DocumentModels.Link> links = db.sql("""
                select object_type, object_id from doc.document_link where document_id = ?
                order by object_type, created_at""")
                .param(documentId)
                .query((rs, n) -> new DocumentModels.Link(rs.getString("object_type"),
                        rs.getObject("object_id", UUID.class)))
                .list();
        return new DocumentModels.Document((UUID) row.get("id"), (UUID) row.get("entity_id"),
                (String) row.get("filename"), (String) row.get("content_type"),
                ((Number) row.get("size_bytes")).longValue(), (String) row.get("sha256"), (String) row.get("kind"),
                (String) row.get("note"), toOffsetDateTime(row.get("uploaded_at")), (UUID) row.get("uploaded_by"),
                links);
    }

    /** Keeps only the file's own name, so {@code ../../etc/passwd} can never reach the filesystem or a header. */
    private static String cleanFilename(String filename) {
        String name = filename == null ? "" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (name.isEmpty()) {
            name = "upload";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private static OffsetDateTime toOffsetDateTime(Object value) {
        return switch (value) {
            case null -> null;
            case OffsetDateTime o -> o;
            case java.sql.Timestamp t -> t.toInstant().atOffset(java.time.ZoneOffset.UTC);
            default -> throw new IllegalStateException("Unexpected timestamp: " + value.getClass());
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
