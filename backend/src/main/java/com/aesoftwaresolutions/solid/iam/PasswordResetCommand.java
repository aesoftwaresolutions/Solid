package com.aesoftwaresolutions.solid.iam;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The way back in when the last administrator is the one locked out.
 *
 * <p>{@code java -jar app.jar --solid.reset-password=someone@example.com} prints a one-time reset link and
 * stops. It needs no login because it needs something better: the ability to run commands on the server. The
 * application does not serve anything in this mode, so the door is open for exactly one command and then
 * closed again.
 */
@Component
@Profile("!test")
class PasswordResetCommand implements ApplicationRunner {

    private final String email;
    private final IamService iam;
    private final PasswordService passwords;
    private final ApplicationContext context;

    PasswordResetCommand(@Value("${solid.reset-password:}") String email, IamService iam,
                         PasswordService passwords, ApplicationContext context) {
        this.email = email;
        this.iam = iam;
        this.passwords = passwords;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email == null || email.isBlank()) {
            return;
        }
        Optional<IamService.User> user = iam.findUserByEmail(email.trim());
        int exitCode = 0;
        if (user.isEmpty()) {
            System.out.println("No account with the address " + email.trim());
            exitCode = 1;
        } else {
            PasswordService.Reset reset = passwords.issueReset(user.get().id(), null, "command line");
            System.out.println();
            System.out.println("Password reset for " + reset.email());
            System.out.println("  valid until: " + reset.expiresAt());
            System.out.println("  open:        /reset-password?token=" + reset.token());
            System.out.println();
            System.out.println("It works once. Signing in still needs the second factor.");
        }
        // Started only to do this, so it stops rather than serving anything.
        System.exit(SpringApplication.exit(context, (ExitCodeGenerator) () -> 0) == 0 ? exitCode : 1);
    }
}
