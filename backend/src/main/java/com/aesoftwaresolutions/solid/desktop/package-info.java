/**
 * Desktop module: what a double-click install needs that an operator would otherwise provide — the bundled
 * database, the master key, the app folder, serving the web UI, and opening the browser (spec 060).
 *
 * <p>Open, because it holds no business logic: it only supplies configuration to everything else.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Desktop",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.aesoftwaresolutions.solid.desktop;
