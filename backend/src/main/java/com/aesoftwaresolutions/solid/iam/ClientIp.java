package com.aesoftwaresolutions.solid.iam;

import jakarta.servlet.http.HttpServletRequest;

/** Client IP for audit logs. Behind Caddy, Spring's forwarded-header support supplies the real address. */
final class ClientIp {

    private ClientIp() {
    }

    static String of(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? null : ip.length() > 64 ? ip.substring(0, 64) : ip;
    }
}
