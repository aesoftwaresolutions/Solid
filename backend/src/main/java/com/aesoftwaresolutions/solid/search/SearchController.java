package com.aesoftwaresolutions.solid.search;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}/entities/{entityId}")
class SearchController {

    private final SearchService search;

    SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping("/search")
    SearchModels.Results search(@PathVariable UUID orgId, @PathVariable UUID entityId,
                                @RequestParam(name = "q", required = false) String q) {
        return search.search(orgId, entityId, q);
    }
}
