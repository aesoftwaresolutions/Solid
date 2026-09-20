/**
 * Migration module: brings an existing chart of accounts, customer list and vendor list in from CSV.
 * It writes through the ledger and billing services, so imported rows obey exactly the same rules as typed ones.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Migration")
package com.aesoftwaresolutions.solid.migration;
