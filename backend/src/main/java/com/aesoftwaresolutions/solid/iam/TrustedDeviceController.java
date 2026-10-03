package com.aesoftwaresolutions.solid.iam;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The trusted-device pair of calls (spec 068). Both live under {@code /auth/mfa/**} because they are part of
 * the second-factor flow: one is how a machine earns the cookie (after a code was just proven), the other is
 * how a fresh pending session trades that cookie for skipping the code this time.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
class TrustedDeviceController {

    static final String COOKIE = "solid_device";

    private final TrustedDeviceService devices;

    TrustedDeviceController(TrustedDeviceService devices) {
        this.devices = devices;
    }

    /**
     * After a successful code or activation, the browser asks for this and holds the answer for 30 days.
     * The token rides a SameSite=Strict HttpOnly cookie scoped to the auth paths; only its hash is stored.
     */
    @PostMapping("/trusted-device")
    ResponseEntity<Map<String, Object>> remember(HttpServletRequest request) {
        SolidPrincipal principal = CurrentUser.require();
        String token = devices.trustThisDevice(principal, ClientIp.of(request),
                request.getHeader(HttpHeaders.USER_AGENT));
        ResponseCookie cookie = ResponseCookie.from(COOKIE, token)
                .httpOnly(true).secure(request.isSecure()).sameSite("Strict")
                .path("/api/v1/auth").maxAge(TrustedDeviceService.TRUSTED_FOR).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("trustedForDays", TrustedDeviceService.TRUSTED_FOR.toDays()));
    }

    /**
     * A fresh sign-in asks: does this machine hold a live trusted-device token for this account? If yes, the
     * pending session becomes verified and the code screen is skipped. Anything else — unknown, expired,
     * another person's — is the same ordinary answer: go get a code.
     */
    @PostMapping("/trusted-check")
    ResponseEntity<Void> check(HttpServletRequest request) {
        SolidPrincipal principal = CurrentUser.require();
        boolean trusted = devices.applyIfTrusted(principal, cookie(request));
        if (!trusted) {
            throw new com.aesoftwaresolutions.solid.common.ApiProblemException(404, "DEVICE_NOT_TRUSTED",
                    "This device is not trusted for this account");
        }
        return ResponseEntity.noContent().build();
    }

    /** Forgets every such cookie this account ever handed out. The ones in browsers stop working at once. */
    @DeleteMapping("/trusted-devices")
    Map<String, Integer> forgetAll(HttpServletRequest request) {
        int forgotten = devices.forgetAll(CurrentUser.require(), ClientIp.of(request));
        return Map.of("forgotten", forgotten);
    }

    private static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
