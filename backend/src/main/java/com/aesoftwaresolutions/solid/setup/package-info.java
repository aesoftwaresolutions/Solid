/**
 * Setup module: answers "what still needs doing before these books are usable?". It owns no tables and
 * writes nothing — every answer is read from the module that owns the records, through its public service.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Setup")
package com.aesoftwaresolutions.solid.setup;
