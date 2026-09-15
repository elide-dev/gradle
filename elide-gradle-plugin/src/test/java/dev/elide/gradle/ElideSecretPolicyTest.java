package dev.elide.gradle;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The redaction policy as a table, so both directions of the tradeoff stay visible: a credential
 * that escapes is a security incident, and an ordinary value that is substituted destroys the
 * diagnostic the log exists for. Cases are drawn from real environments.
 */
class ElideSecretPolicyTest {
    /** Values that must never reach a build log. */
    @ParameterizedTest(name = "{0}={1}")
    @CsvSource(delimiter = '|', value = {
            // Named outright.
            "GITHUB_TOKEN|ghp_0123456789abcdefghijklmnopqrstuvwxyz",
            "AWS_SECRET_ACCESS_KEY|wJalrXUtnFEMIK7MDENGbPxRfiCYEXAMPLEKEY",
            "API_KEY|0123456789abcdef",
            "MY_PASSWORD|hunter2hunter2",
            "SIGNING_PASSPHRASE|correct horse battery staple",
            "NPM_AUTH_TOKEN|npm_0123456789abcdefghij",
            "SESSION_COOKIE|abcdef0123456789",
            "GPG_PRIVATE_KEY|-----BEGIN PGP PRIVATE KEY BLOCK-----",
            // Named by convention rather than by the word 'token' or 'secret'.
            "GH_PAT|somewhat-opaque-value",
            "GITHUB_PAT|another-opaque-value",
            "SENTRY_DSN|https://examplePublicKey@o0.ingest.sentry.io/0",
            "TWILIO_ACCOUNT_SID|not-a-real-account-identifier",
            "SLACK_BEARER|abcdef0123456789",
            // Credential shape under a name that says nothing.
            "BUILD_INPUT|ghp_0123456789abcdefghijklmnopqrstuvwxyz",
            "DATABASE_URL|postgres://admin:s3cr3t@db.internal:5432/app",
            "ANYTHING|xoxb-0123456789-abcdefghijkl",
            "OPAQUE|eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.signature",
            // Words glued together inside one segment, which is how Gradle receives credentials
            // from the environment. These have no PASSWORD or TOKEN segment to match.
            "ORG_GRADLE_PROJECT_signingPassword|s3cr3t-passphrase",
            "ORG_GRADLE_PROJECT_sonatypePassword|s3cr3t-passphrase",
            "ORG_GRADLE_PROJECT_mavenCentralPassword|s3cr3t-passphrase",
            "ORG_GRADLE_PROJECT_signingKeyId|0A1B2C3D",
            "ORG_GRADLE_PROJECT_signingInMemoryKey|LS0tLS1CRUdJTiBQR1AgUFJJVkFURQ==",
            "AWS_SECRETACCESSKEY|wJalrXUtnFEMIK7MDENGbPxRfiCYEXAMPLEKEY",
            "GITHUB_ACCESSTOKEN|0123456789abcdef0123",
            "myApiToken|0123456789abcdef0123",
            "signingPassword|s3cr3t-passphrase",
            "my_private_key|LS0tLS1CRUdJTiBQR1A=",
    })
    void redactsCredentialBearingValues(String name, String value) {
        assertTrue(ElideSecretPolicy.isSensitive(name, value),
                () -> name + " must be redacted");
    }

    /** Values that must survive, or the diagnostic they appear in becomes unreadable. */
    @ParameterizedTest(name = "{0}={1}")
    @CsvSource(delimiter = '|', value = {
            // Ordinary environment noise that collided with the previous substring matching.
            "XDG_SESSION_TYPE|wayland",
            "XDG_SESSION_ID|12345678",
            "SESSION_MANAGER|local/host:@/tmp/.ICE-unix/1234",
            "DBUS_SESSION_BUS_ADDRESS|unix:path=/run/user/1000/bus",
            "SSH_AUTH_SOCK|/tmp/ssh-XXXXXXXX/agent.1234",
            "KDE_SESSION_VERSION|6",
            // Words that merely contain a sensitive fragment.
            "KEYBOARD_LAYOUT|us-intl",
            "MONKEY_PATCH_MODE|enabled",
            "KEYMAP_PATH|/usr/share/keymaps",
            // Everyday variables whose values appear verbatim in compiler output.
            "PWD|/home/datafox/project",
            "USER|datafox",
            "HOME|/home/datafox",
            "DISPLAY|:1",
            "LC_TIME|C",
            "TERM|xterm-256color",
            "JAVA_HOME|/usr/lib/jvm/java-17",
            "LANG|en_US.UTF-8",
            // A URL without inline credentials is not a credential.
            "DATABASE_URL|postgres://db.internal:5432/app",
            "PROXY_URL|http://proxy.internal:3128",
            // Below the length floor, where substitution is pure collateral damage. This applies to
            // credential-shaped values too: some prefixes are only three or four bytes long, and
            // redacting every occurrence of "eyJ" would shred ordinary output.
            "SECRET_FLAG|1",
            "AUTH_MODE|off",
            "OPAQUE|eyJ",
            "OPAQUE|sk-1",
    })
    void preservesOrdinaryValues(String name, String value) {
        assertFalse(ElideSecretPolicy.isSensitive(name, value),
                () -> name + " must not be redacted");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "SECRET|",
            "|value",
    })
    void ignoresEmptyNamesAndValues(String name, String value) {
        assertFalse(ElideSecretPolicy.isSensitive(name, value));
    }

    @org.junit.jupiter.api.Test
    void redactsAShortMultibyteSecretAboveTheByteFloor() {
        // Three characters, nine UTF-8 bytes: the floor is measured in bytes so this still counts.
        assertTrue(ElideSecretPolicy.isSensitive("API_SECRET", "界界界"));
    }
}
