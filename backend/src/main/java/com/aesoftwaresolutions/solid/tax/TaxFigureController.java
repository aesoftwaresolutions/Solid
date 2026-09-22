package com.aesoftwaresolutions.solid.tax;

import com.aesoftwaresolutions.solid.common.ApiProblemException;
import com.aesoftwaresolutions.solid.common.ForbiddenException;
import com.aesoftwaresolutions.solid.platform.RequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Adding this year's published figures without rebuilding the application (spec 049). */
@RestController
@RequestMapping("/api/v1/instance/tax-figures")
class TaxFigureController {

    record AddFigure(@NotBlank String key, @NotNull Integer taxYear, @NotBlank String value,
                     @NotBlank String source, String note, Boolean supersede) {
    }

    private final TaxFigures figures;

    TaxFigureController(TaxFigures figures) {
        this.figures = figures;
    }

    @GetMapping
    List<TaxFigures.Figure> list() {
        requireInstanceAdmin();
        return figures.all();
    }

    /** What this version understands, so a screen can offer the right fields instead of guessing. */
    @GetMapping("/keys")
    List<Map<String, Object>> keys() {
        requireInstanceAdmin();
        return Arrays.stream(TaxFigures.Key.values())
                .map(key -> Map.<String, Object>of("key", key.name(), "unit", key.unit(),
                        "decimalPlaces", key.scale()))
                .toList();
    }

    @PostMapping
    TaxFigures.Figure add(@Valid @RequestBody AddFigure body) {
        // Who and from where comes from the platform's request context, so this module needs nothing from iam.
        RequestContext.Caller caller = requireInstanceAdmin();
        return figures.add(body.key(), body.taxYear(), body.value(), body.source(), body.note(),
                Boolean.TRUE.equals(body.supersede()), caller.userId(), caller.ip());
    }

    private static RequestContext.Caller requireInstanceAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ApiProblemException(401, "UNAUTHENTICATED", "Please log in");
        }
        boolean admin = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch("INSTANCE_ADMIN"::equals);
        if (!admin) {
            throw new ForbiddenException("Only an instance administrator can manage tax figures");
        }
        return RequestContext.current()
                .orElseThrow(() -> new ApiProblemException(401, "UNAUTHENTICATED", "Please log in"));
    }
}
