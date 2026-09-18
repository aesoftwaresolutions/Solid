package com.aesoftwaresolutions.solid.ai;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}")
class SuggestionController {

    private final SuggestionService suggestions;

    SuggestionController(SuggestionService suggestions) {
        this.suggestions = suggestions;
    }

    @PostMapping("/bank-transactions/{txnId}/suggest")
    SuggestionService.Suggestion suggest(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                         @PathVariable UUID txnId) {
        return suggestions.suggestCategory(orgId, entityId, txnId);
    }
}
