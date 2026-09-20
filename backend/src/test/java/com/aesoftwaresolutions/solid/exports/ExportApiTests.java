package com.aesoftwaresolutions.solid.exports;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;

/** Spec 023. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ExportApiTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String org;
    String entity;
    String base;
    Map<String, String> acct = new HashMap<>();

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        org = api.newOrg();
        entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
    }

    private void post(String date, String debitCode, String creditCode, String amount, String memo) {
        Map<String, Object> body = new HashMap<>();
        body.put("entryDate", date);
        body.put("post", true);
        body.put("memo", memo);
        body.put("lines", List.of(
                Map.of("accountId", acct.get(debitCode), "amount", Map.of("amount", amount, "currency", "USD")),
                Map.of("accountId", acct.get(creditCode), "amount", Map.of("amount", "-" + amount, "currency", "USD"))));
        api.post(base + "/journal-entries", body, HttpStatus.CREATED);
    }

    private Map<String, String> download(String path, HttpStatus expected) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(api.token());
        ResponseEntity<byte[]> response = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        if (expected != HttpStatus.OK) {
            return Map.of();
        }
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.getBody()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    /** A small RFC 4180 reader, so the test parses the file the way a spreadsheet would. */
    private static List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private static List<String> findRow(String csv, int column, String value) {
        return parse(csv).stream().filter(row -> row.size() > column && row.get(column).equals(value)).findFirst()
                .orElseThrow(() -> new AssertionError("No row with " + value + " in\n" + csv));
    }

    @Test
    void ac1_ac2_theZipHoldsTheBooksAsJoinableCsv() throws IOException {
        post("2026-03-01", "6220", "1010", "54.99", "Adobe subscription");

        Map<String, String> files = download(base + "/export.zip", HttpStatus.OK);
        assertThat(files.keySet()).containsExactlyInAnyOrder("README.txt", "accounts.csv", "journal-entries.csv",
                "journal-lines.csv", "bank-transactions.csv", "invoices.csv", "bills.csv", "documents.csv");
        files.forEach((name, content) -> {
            if (name.endsWith(".csv")) {
                assertThat(content).as(name + " has a header").startsWith(parse(content).get(0).get(0));
                assertThat(parse(content).get(0)).isNotEmpty();
            }
        });

        List<String> software = findRow(files.get("accounts.csv"), 1, "6220");
        assertThat(software.get(2)).isEqualTo("Software and Subscriptions");

        List<List<String>> lines = parse(files.get("journal-lines.csv"));
        List<String> debit = lines.stream().filter(r -> r.get(2).equals(acct.get("6220"))).findFirst().orElseThrow();
        assertThat(debit.get(3)).as("plain decimal string").isEqualTo("54.99");
        assertThat(debit.get(4)).isEqualTo("USD");

        String entryId = debit.get(0);
        assertThat(findRow(files.get("journal-entries.csv"), 0, entryId).get(2)).isEqualTo("Adobe subscription");
    }

    @Test
    void ac3_ac4_awkwardTextSurvivesAndFormulasCannotRun() throws IOException {
        post("2026-03-02", "6220", "1010", "10.00", "Says \"hi\", then\nnewline");
        post("2026-03-03", "6220", "1010", "11.00", "=SUM(A1:A9)");

        Map<String, String> files = download(base + "/export.zip", HttpStatus.OK);
        List<List<String>> entries = parse(files.get("journal-entries.csv"));

        assertThat(entries.stream().map(r -> r.get(2)))
                .as("quotes, commas and newlines come back unchanged")
                .contains("Says \"hi\", then\nnewline");
        assertThat(entries.stream().map(r -> r.get(2)))
                .as("a formula is neutralised with a leading apostrophe")
                .contains("'=SUM(A1:A9)")
                .doesNotContain("=SUM(A1:A9)");
    }

    @Test
    void negativeAmountsStayNumbers() throws IOException {
        post("2026-03-04", "6220", "1010", "54.99", "Adobe");

        Map<String, String> files = download(base + "/export.zip", HttpStatus.OK);
        List<List<String>> lines = parse(files.get("journal-lines.csv"));
        List<String> credit = lines.stream().filter(r -> r.get(2).equals(acct.get("1010"))).findFirst().orElseThrow();

        assertThat(credit.get(3))
                .as("an apostrophe here would make every credit text a spreadsheet will not add up")
                .isEqualTo("-54.99");
        assertThat(files.get("journal-lines.csv")).doesNotContain("'-54.99");
    }

    @Test
    void ac5_documentsAreMetadataOnly() throws IOException {
        byte[] png = new byte[64];
        System.arraycopy(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, 0, png, 0, 4);
        for (int i = 4; i < png.length; i++) {
            png[i] = (byte) i;
        }
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(png) {
            @Override
            public String getFilename() {
                return "receipt.png";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(api.token());
        assertThat(rest.exchange(base + "/documents?kind=receipt", HttpMethod.POST, new HttpEntity<>(form, headers),
                String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, String> files = download(base + "/export.zip", HttpStatus.OK);
        List<String> row = findRow(files.get("documents.csv"), 1, "receipt.png");
        assertThat(row.get(2)).isEqualTo("receipt");
        assertThat(row.get(4)).isEqualTo(String.valueOf(png.length));
        assertThat(row.get(5)).hasSize(64);
        assertThat(files.get("documents.csv")).doesNotContain(new String(png, StandardCharsets.ISO_8859_1));
        assertThat(files.get("README.txt")).contains("docs/operations.md");
    }

    @Test
    void ac6_ac7_accessIsScopedAndAnEmptyEntityStillExports() throws IOException {
        String empty = api.newEntity(org, "sole_prop");
        Map<String, String> files = download("/api/v1/orgs/" + org + "/entities/" + empty + "/export.zip",
                HttpStatus.OK);
        assertThat(parse(files.get("journal-lines.csv"))).hasSize(1);
        assertThat(parse(files.get("accounts.csv"))).hasSize(1);

        ApiClient outsider = new ApiClient(rest);
        outsider.get(base + "/export.zip", HttpStatus.NOT_FOUND);
        new ApiClient(rest, false).get(base + "/export.zip", HttpStatus.UNAUTHORIZED);
    }
}
