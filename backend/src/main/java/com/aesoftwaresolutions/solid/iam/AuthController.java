package com.aesoftwaresolutions.solid.iam;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    record SignupRequest(@NotBlank @Size(max = 254) String email, @NotBlank String password,
                         @NotBlank @Size(max = 120) String displayName) {
    }

    record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    record CodeRequest(@Size(max = 20) String code, @Size(max = 20) String recoveryCode) {
    }

    private final IamService iam;
    private final MembershipService memberships;
    private final InvitationService invitations;
    private final PasswordService passwords;

    AuthController(IamService iam, MembershipService memberships, InvitationService invitations,
                   PasswordService passwords) {
        this.invitations = invitations;
        this.passwords = passwords;
        this.iam = iam;
        this.memberships = memberships;
    }

    record AcceptInvitationRequest(@NotBlank String token, @NotBlank String displayName,
                                   @NotBlank String password) {
    }

    /**
     * Accepting an invitation is unauthenticated on purpose: the person has no account yet. The token is the
     * only credential, and it decides which address and which organization — nothing here is taken on trust
     * from the request.
     */
    @PostMapping("/accept-invitation")
    InvitationService.Acceptance acceptInvitation(@Valid @RequestBody AcceptInvitationRequest body,
                                                  HttpServletRequest request) {
        return invitations.accept(body.token(), body.displayName(), body.password(), ClientIp.of(request));
    }

    record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    record ResetPasswordRequest(@NotBlank String token, @NotBlank String newPassword) {
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request) {
        passwords.change(CurrentUser.require(), body.currentPassword(), body.newPassword(), ClientIp.of(request));
    }

    /**
     * Spends a reset link. Unauthenticated, because the whole point is that the person cannot sign in — and
     * it grants nothing: they still have to log in, and still have to pass MFA.
     */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@Valid @RequestBody ResetPasswordRequest body, HttpServletRequest request) {
        passwords.useReset(body.token(), body.newPassword(), ClientIp.of(request));
    }

    @PostMapping("/signup")
    ResponseEntity<IamService.User> signup(@Valid @RequestBody SignupRequest body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(iam.signup(body.email(), body.password(), body.displayName(), ClientIp.of(request)));
    }

    @PostMapping("/login")
    ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        IamService.LoginResult result = iam.login(body.email(), body.password(), ClientIp.of(request),
                request.getHeader(HttpHeaders.USER_AGENT));
        ResponseCookie cookie = ResponseCookie.from(SessionAuthenticationFilter.COOKIE, result.token())
                .httpOnly(true).secure(request.isSecure()).sameSite("Strict").path("/")
                .maxAge(Duration.ofHours(12)).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("mfaEnrolled", result.mfaEnrolled(), "token", result.token()));
    }

    @PostMapping("/mfa/enroll")
    IamService.Enrollment enroll() {
        return iam.enrollMfa(CurrentUser.require());
    }

    @PostMapping("/mfa/activate")
    Map<String, List<String>> activate(@Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        return Map.of("recoveryCodes", iam.activateMfa(CurrentUser.require(), body.code(), ClientIp.of(request)));
    }

    @PostMapping("/mfa/verify")
    ResponseEntity<Void> verify(@Valid @RequestBody CodeRequest body, HttpServletRequest request) {
        iam.verifyMfa(CurrentUser.require(), body.code(), body.recoveryCode(), ClientIp.of(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        iam.logout(CurrentUser.require(), ClientIp.of(request));
        ResponseCookie expired = ResponseCookie.from(SessionAuthenticationFilter.COOKIE, "")
                .httpOnly(true).secure(request.isSecure()).sameSite("Strict").path("/").maxAge(0).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expired.toString()).build();
    }

    @GetMapping("/me")
    Map<String, Object> me() {
        SolidPrincipal p = CurrentUser.require();
        IamService.User user = iam.findUser(p.userId()).orElseThrow();
        return Map.of("user", user, "mfaVerified", p.mfaVerified(),
                "organizationIds", p.mfaVerified() ? memberships.orgIdsFor(p.userId()) : List.of());
    }
}
