package com.aesoftwaresolutions.solid.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Loads chart-of-accounts templates from {@code classpath:coa-templates/<name>.json}. */
@Component
class CoaTemplates {

    private static final Pattern NAME = Pattern.compile("[a-z_]{1,40}");

    record TemplateAccount(String code, String name, AccountType type, String subtype, String parentCode,
                           boolean header, String taxLineCode) {
    }

    record Template(String name, Set<String> applicableEntityKinds, List<TemplateAccount> accounts) {
    }

    private final ObjectMapper mapper;

    CoaTemplates(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Optional<Template> find(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        ClassPathResource resource = new ClassPathResource("coa-templates/" + name + ".json");
        if (!resource.exists()) {
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = mapper.readTree(in);
            List<String> kinds = new ArrayList<>();
            root.get("applicableEntityKinds").forEach(k -> kinds.add(k.asText()));
            List<TemplateAccount> accounts = new ArrayList<>();
            for (JsonNode a : root.get("accounts")) {
                accounts.add(new TemplateAccount(
                        a.get("code").asText(),
                        a.get("name").asText(),
                        AccountType.valueOf(a.get("type").asText()),
                        text(a, "subtype"),
                        text(a, "parent"),
                        a.path("header").asBoolean(false),
                        text(a, "taxLine")));
            }
            return Optional.of(new Template(name, Set.copyOf(kinds), List.copyOf(accounts)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read COA template " + name, e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
