package com.aesoftwaresolutions.solid.docs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}")
class DocumentController {

    record LinkRequest(@NotBlank String objectType, @NotNull UUID objectId) {
    }

    private final DocumentService documents;

    DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @PostMapping(path = "/documents", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    DocumentModels.Document upload(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                   @RequestPart("file") MultipartFile file,
                                   @RequestParam String kind,
                                   @RequestParam(required = false) @Size(max = 500) String note) throws IOException {
        if (file.getSize() > DocumentService.MAX_FILE_BYTES) {
            throw new com.aesoftwaresolutions.solid.common.ApiProblemException(400, "FILE_TOO_LARGE",
                    "Files must be 25 MB or smaller");
        }
        return documents.upload(orgId, entityId, file.getOriginalFilename(), kind, note, file.getBytes());
    }

    @GetMapping("/documents")
    List<DocumentModels.Document> list(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                       @RequestParam(required = false) String kind,
                                       @RequestParam(required = false) String linkedType,
                                       @RequestParam(required = false) UUID linkedId) {
        return documents.list(orgId, entityId, kind, linkedType, linkedId);
    }

    @GetMapping("/documents/{documentId}")
    DocumentModels.Document get(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                @PathVariable UUID documentId) {
        return documents.get(orgId, entityId, documentId);
    }

    @GetMapping("/documents/{documentId}/content")
    ResponseEntity<Resource> content(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                     @PathVariable UUID documentId) {
        DocumentModels.Content content = documents.content(orgId, entityId, documentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(content.filename()).build().toString())
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.bytes().length)
                .body(new ByteArrayResource(content.bytes()));
    }

    @PostMapping("/documents/{documentId}/links")
    DocumentModels.Document link(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                 @PathVariable UUID documentId, @Valid @RequestBody LinkRequest body) {
        return documents.link(orgId, entityId, documentId, body.objectType(), body.objectId());
    }

    @DeleteMapping("/documents/{documentId}/links/{objectType}/{objectId}")
    DocumentModels.Document unlink(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                   @PathVariable UUID documentId, @PathVariable String objectType,
                                   @PathVariable UUID objectId) {
        return documents.unlink(orgId, entityId, documentId, objectType, objectId);
    }

    @DeleteMapping("/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID orgId, @PathVariable UUID entityId, @PathVariable UUID documentId) {
        documents.delete(orgId, entityId, documentId);
    }
}
