/**
 * Search module: one query across the books. It owns no tables — each module that owns records implements
 * {@link com.aesoftwaresolutions.solid.search.SearchProvider} for its own, so module boundaries hold.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Search")
package com.aesoftwaresolutions.solid.search;
