package com.aesoftwaresolutions.solid.tax;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/** Read-only catalog of tax form lines, loaded from {@code classpath:tax-lines/*.json} at startup. */
@Component
public class TaxLineCatalog {

    private final Map<String, TaxLine> byCode;

    public TaxLineCatalog(ObjectMapper mapper) {
        this.byCode = Collections.unmodifiableMap(load(mapper));
    }

    public Optional<TaxLine> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    public List<TaxLine> forForm(String form) {
        return byCode.values().stream().filter(l -> l.form().equals(form)).toList();
    }

    public List<TaxLine> all() {
        return List.copyOf(byCode.values());
    }

    private static Map<String, TaxLine> load(ObjectMapper mapper) {
        Map<String, TaxLine> lines = new LinkedHashMap<>();
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:tax-lines/*.json");
            for (Resource file : files) {
                try (InputStream in = file.getInputStream()) {
                    JsonNode root = mapper.readTree(in);
                    String form = root.get("form").asText();
                    for (JsonNode node : root.get("lines")) {
                        String line = node.get("line").asText();
                        String code = form + "." + line;
                        TaxLine taxLine = new TaxLine(code, form, line, node.get("label").asText(),
                                TaxLine.Kind.valueOf(node.get("kind").asText()));
                        if (lines.put(code, taxLine) != null) {
                            throw new IllegalStateException("Duplicate tax line code " + code);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load tax line catalog", e);
        }
        return lines;
    }
}
