package com.aesoftwaresolutions.solid.platform;

/**
 * Basic information about the running instance.
 *
 * @param name                  product name
 * @param version               application version from the build
 * @param databaseSchemaVersion latest applied Flyway migration version
 * @param desktopMode           whether this is the desktop build (only there can the database be reconfigured
 *                              from Settings; a server install is always its operator's own environment)
 */
public record SystemInfo(String name, String version, String databaseSchemaVersion, boolean desktopMode) {
}
