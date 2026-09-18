package com.aesoftwaresolutions.solid.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Spec 016. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DocumentApiTests {

    private static final byte[] PNG = png();

    @Autowired
    TestRestTemplate rest;

    @Value("${solid.documents.root}")
    String documentsRoot;

    ApiClient api;
    String org;
    String entity;
    String base;

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        org = api.newOrg();
        entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
    }

    private static byte[] png() {
        byte[] bytes = new byte[512];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        for (int i = magic.length; i < bytes.length; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }

    private ResponseEntity<JsonNode> upload(String filename, byte[] bytes, String kind, String token) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        ByteArrayResource part = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        form.add("file", part);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        return rest.exchange(base + "/documents?kind=" + kind, HttpMethod.POST, new HttpEntity<>(form, headers),
                JsonNode.class);
    }

    private JsonNode uploadOk(String filename, byte[] bytes, String kind) {
        ResponseEntity<JsonNode> response = upload(filename, bytes, kind, api.token());
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private List<Path> storedFiles() throws IOException {
        Path root = Path.of(documentsRoot).resolve(org);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void ac1_uploadsAndDownloadsByteIdenticallyWhileTheDiskHoldsCiphertext() throws IOException {
        JsonNode document = uploadOk("receipt.png", PNG, "receipt");
        assertThat(document.get("contentType").asText()).isEqualTo("image/png");
        assertThat(document.get("sizeBytes").asLong()).isEqualTo(PNG.length);
        assertThat(document.get("filename").asText()).isEqualTo("receipt.png");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(api.token());
        ResponseEntity<byte[]> content = rest.exchange(base + "/documents/" + document.get("id").asText() + "/content",
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertThat(content.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content.getBody()).isEqualTo(PNG);
        assertThat(content.getHeaders().getFirst("Content-Disposition")).contains("receipt.png");

        List<Path> files = storedFiles();
        assertThat(files).hasSize(1);
        byte[] onDisk = Files.readAllBytes(files.get(0));
        assertThat(onDisk).isNotEqualTo(PNG);
        assertThat(onDisk.length).isGreaterThan(PNG.length);
        assertThat(files.get(0).getFileName().toString()).isEqualTo(document.get("id").asText());
    }

    @Test
    void ac2_rejectsDisguisedAndOversizedFiles() {
        byte[] binary = {0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00};
        ResponseEntity<JsonNode> disguised = upload("invoice.pdf", binary, "receipt", api.token());
        assertThat(disguised.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(disguised.getBody().get("code").asText()).isEqualTo("UNSUPPORTED_FILE_TYPE");

        byte[] huge = new byte[26 * 1024 * 1024];
        System.arraycopy(PNG, 0, huge, 0, 8);
        assertThat(upload("big.png", huge, "receipt", api.token()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac3_identicalBytesAreStoredOnce() throws IOException {
        JsonNode first = uploadOk("receipt.png", PNG, "receipt");
        JsonNode second = uploadOk("same-receipt-renamed.png", PNG, "other");
        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(second.get("kind").asText()).as("the original metadata wins").isEqualTo("receipt");
        assertThat(api.get(base + "/documents")).hasSize(1);
        assertThat(storedFiles()).hasSize(1);
    }

    @Test
    void ac4_linksToRecordsAndFiltersByThem() {
        String id = uploadOk("receipt.png", PNG, "receipt").get("id").asText();
        String entryId = UUID.randomUUID().toString();
        String txnId = UUID.randomUUID().toString();

        api.post(base + "/documents/" + id + "/links",
                Map.of("objectType", "journal_entry", "objectId", entryId), HttpStatus.OK);
        api.post(base + "/documents/" + id + "/links",
                Map.of("objectType", "bank_transaction", "objectId", txnId), HttpStatus.OK);
        JsonNode again = api.post(base + "/documents/" + id + "/links",
                Map.of("objectType", "journal_entry", "objectId", entryId), HttpStatus.OK);
        assertThat(again.get("links")).as("duplicate links are ignored").hasSize(2);

        assertThat(api.get(base + "/documents?linkedType=journal_entry&linkedId=" + entryId)).hasSize(1);
        assertThat(api.get(base + "/documents?linkedType=journal_entry&linkedId=" + UUID.randomUUID())).isEmpty();
        assertThat(api.get(base + "/documents?kind=receipt")).hasSize(1);
        assertThat(api.get(base + "/documents?kind=w2")).isEmpty();
    }

    @Test
    void ac5_deleteIsRefusedWhileLinkedAndRemovesTheFileAfterwards() throws IOException {
        String id = uploadOk("receipt.png", PNG, "receipt").get("id").asText();
        String entryId = UUID.randomUUID().toString();
        api.post(base + "/documents/" + id + "/links",
                Map.of("objectType", "journal_entry", "objectId", entryId), HttpStatus.OK);

        JsonNode refused = api.delete(base + "/documents/" + id, HttpStatus.CONFLICT);
        assertThat(refused.get("code").asText()).isEqualTo("DOCUMENT_LINKED");

        JsonNode unlinked = api.delete(base + "/documents/" + id + "/links/journal_entry/" + entryId, HttpStatus.OK);
        assertThat(unlinked.get("links")).isEmpty();

        api.delete(base + "/documents/" + id, HttpStatus.NO_CONTENT);
        api.get(base + "/documents/" + id, HttpStatus.NOT_FOUND);
        assertThat(storedFiles()).isEmpty();
    }

    @Test
    void ac6_uploadAndDownloadAreAudited() {
        String id = uploadOk("receipt.png", PNG, "receipt").get("id").asText();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(api.token());
        rest.exchange(base + "/documents/" + id + "/content", HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

        List<String> actions = new ArrayList<>();
        String payload = new String(PNG, StandardCharsets.ISO_8859_1);
        JsonNode events = api.get("/api/v1/orgs/" + org + "/audit-events");
        events.forEach(e -> actions.add(e.get("action").asText()));
        assertThat(actions).contains("document_uploaded", "document_downloaded");
        assertThat(events.toString()).as("file contents never reach the audit log").doesNotContain(payload);
    }

    @Test
    void ac7_anotherOrganizationCannotSeeTheDocument() {
        String id = uploadOk("receipt.png", PNG, "receipt").get("id").asText();
        ApiClient outsider = new ApiClient(rest);
        outsider.get(base + "/documents/" + id, HttpStatus.NOT_FOUND);
        outsider.get(base + "/documents", HttpStatus.NOT_FOUND);
        assertThat(upload("receipt.png", PNG, "receipt", outsider.token()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
