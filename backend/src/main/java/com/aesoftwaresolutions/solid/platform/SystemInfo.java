package com.aesoftwaresolutions.solid.platform;

/**
 * Basic information about the running instance.
 *
 * @param name                  product name
 * @param version               application version from the build
 * @param databaseSchemaVersion latest applied Flyway migration version
 */
public record SystemInfo(String name, String version, String databaseSchemaVersion) {
}
