package com.aesoftwaresolutions.solid.tax;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The registry of tax rule files in {@code classpath:tax-rules/}.
 *
 * <p>Every tax figure Solid uses lives in one of those files next to a written source. This class checks that
 * rule at startup — a file with no source, or with impossible or duplicated years, stops the application — and
 * reports what the installation knows about a given tax year. It contains no tax figures of its own and never
 * infers one for a year the files do not list.
 */
@Component
public class TaxRulePacks {

    /** The first year a federal income tax return existed, and a far-future bound: anything outside is a typo. */
    private static final int EARLIEST_YEAR = 1913;
    private static final int LATEST_YEAR = 2100;

    public record RulePack(String id, String title, String source, List<Integer> taxYears, Integer appliesFromTaxYear,
                           List<String> todos) {

        /** True when this pack states a figure for that year — never by interpolation. */
        public boolean covers(int taxYear) {
            return taxYears.contains(taxYear) || (appliesFromTaxYear != null && taxYear >= appliesFromTaxYear);
        }
    }

    public record Coverage(String id, String title, boolean covered, String reason, String source,
                           List<String> todos) {
    }

    public record CoverageReport(int taxYear, List<Coverage> packs, int covered, int missing, String note) {
    }

    static final String NOTE = "Anything reported as missing has no figure on file for that year. Solid will not "
            + "guess one: add it from the cited IRS source (and have it reviewed) before relying on a projection "
            + "or filing anything.";

    private final List<RulePack> packs;

    TaxRulePacks(ObjectMapper mapper) {
        this.packs = load(mapper);
    }

    public List<RulePack> all() {
        return packs;
    }

    public Optional<RulePack> find(String id) {
        return packs.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public CoverageReport coverage(int taxYear) {
        if (taxYear < EARLIEST_YEAR || taxYear > LATEST_YEAR) {
            throw new IllegalArgumentException("taxYear must be between " + EARLIEST_YEAR + " and " + LATEST_YEAR);
        }
        List<Coverage> rows = new ArrayList<>();
        int covered = 0;
        for (RulePack pack : packs) {
            boolean hit = pack.covers(taxYear);
            covered += hit ? 1 : 0;
            String reason = hit
                    ? (pack.taxYears().contains(taxYear) ? "A figure is on file for " + taxYear
                            : "Applies from " + pack.appliesFromTaxYear() + " until changed")
                    : "No figure on file for " + taxYear;
            rows.add(new Coverage(pack.id(), pack.title(), hit, reason, pack.source(), pack.todos()));
        }
        return new CoverageReport(taxYear, List.copyOf(rows), covered, packs.size() - covered, NOTE);
    }

    private static List<RulePack> load(ObjectMapper mapper) {
        List<RulePack> loaded = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:tax-rules/*.json");
            for (Resource resource : resources) {
                String id = stem(resource.getFilename());
                try (InputStream in = resource.getInputStream()) {
                    loaded.add(parse(id, mapper.readTree(in)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the tax rule files", e);
        }
        loaded.sort(Comparator.comparing(RulePack::id));
        return List.copyOf(loaded);
    }

    /** Package-private so the validation rules can be tested directly against JSON. */
    static RulePack parse(String id, JsonNode root) {
        String source = root.path("source").asText("").trim();
        if (source.isEmpty()) {
            throw new IllegalStateException("Tax rule file '" + id + "' has no 'source'. Every tax figure must name "
                    + "the IRS publication, notice or law it comes from (see CLAUDE.md).");
        }
        String title = firstText(root, "form", "deduction", "title", "description").orElse(id);

        Set<Integer> years = new TreeSet<>();
        collectYears(id, root, years, new java.util.HashSet<>());
        Integer appliesFrom = root.hasNonNull("appliesFromTaxYear") ? root.get("appliesFromTaxYear").asInt() : null;
        if (appliesFrom != null) {
            checkYear(id, appliesFrom);
        }

        List<String> todos = new ArrayList<>();
        root.path("todo").forEach(node -> todos.add(node.asText()));
        return new RulePack(id, title, source, List.copyOf(years), appliesFrom, List.copyOf(todos));
    }

    /** Walks the file looking for {@code taxYear} fields, so a new file shape needs no code change. */
    private static void collectYears(String id, JsonNode node, Set<Integer> into, Set<Integer> seenInThisList) {
        if (node.isObject()) {
            if (node.hasNonNull("taxYear")) {
                int year = node.get("taxYear").asInt();
                checkYear(id, year);
                if (!seenInThisList.add(year)) {
                    throw new IllegalStateException("Tax rule file '" + id + "' lists tax year " + year + " twice; "
                            + "one year cannot have two figures on file.");
                }
                into.add(year);
            }
            node.fields().forEachRemaining(entry -> collectYears(id, entry.getValue(), into, seenInThisList));
        } else if (node.isArray()) {
            // Each list gets its own duplicate check, so two different lists may both mention the same year.
            Set<Integer> seenHere = new java.util.HashSet<>();
            node.forEach(child -> collectYears(id, child, into, seenHere));
        }
    }

    private static void checkYear(String id, int year) {
        if (year < EARLIEST_YEAR || year > LATEST_YEAR) {
            throw new IllegalStateException("Tax rule file '" + id + "' has tax year " + year
                    + ", which is outside " + EARLIEST_YEAR + "-" + LATEST_YEAR + " — that is a typo.");
        }
    }

    private static Optional<String> firstText(JsonNode root, String... fields) {
        for (String field : fields) {
            String value = root.path(field).asText("").trim();
            if (!value.isEmpty()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static String stem(String filename) {
        String name = filename == null ? "unknown" : filename;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
