package com.aesoftwaresolutions.solid.docs;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** What the document API returns. The file's bytes are only served by the {@code /content} endpoint. */
public final class DocumentModels {

    private DocumentModels() {
    }

    /** A link from a document to the record it belongs to (a journal entry, invoice, and so on). */
    public record Link(String objectType, UUID objectId) {
    }

    public record Document(UUID id, UUID entityId, String filename, String contentType, long sizeBytes, String sha256,
                           String kind, String note, OffsetDateTime uploadedAt, UUID uploadedBy, List<Link> links) {
    }

    /** The decrypted bytes plus what the browser needs to display or save them. */
    public record Content(String filename, String contentType, byte[] bytes) {
    }
}
