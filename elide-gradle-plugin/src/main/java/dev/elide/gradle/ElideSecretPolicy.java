package dev.elide.gradle;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether an inherited environment value must be kept out of failure diagnostics.
 *
 * <p>This is a pure predicate so the policy can be reviewed and tested directly, rather than only
 * through a full build fixture. It has to satisfy two opposing requirements: a value that carries a
 * credential must never reach a build log, and a value that does not must never be substituted into
 * one, because redacting ordinary values destroys the diagnostic the log exists for.
 *
 * <p>Two independent triggers are used, either of which is sufficient.
 *
 * <ol>
 *   <li><b>Name segments.</b> The name is split on {@code _}, {@code -} and {@code .}, and a
 *       segment must match exactly. Substring matching was the previous approach and it fired on
 *       {@code KEYBOARD} and {@code MONKEY}.
 *   <li><b>Value shape.</b> Some names carry credentials without saying so — {@code GH_PAT},
 *       {@code DATABASE_URL} — so a value that looks like a credential is redacted whatever it is
 *       called.
 * </ol>
 *
 * <p>The two lists are deliberately asymmetric. Missing a sensitive name leaks a secret, so that
 * list is broad; missing a benign name only mangles a diagnostic, so that list can stay short and
 * be extended as false positives are found.
 */
final class ElideSecretPolicy {
    /**
     * Values shorter than this carry no secret but collide with ordinary diagnostic text: a
     * one-character {@code LC_TIME=C} or a two-character {@code DISPLAY=:1} would otherwise be
     * substituted throughout a compiler error. Measured in UTF-8 bytes so short multibyte secrets
     * are still redacted.
     */
    static final int MIN_VALUE_BYTES = 6;

    /** Name segments that indicate the value is a credential. */
    private static final Set<String> SENSITIVE_SEGMENTS = Set.of(
            "TOKEN", "TOKENS", "SECRET", "SECRETS", "PASSWORD", "PASSWD", "PASSPHRASE",
            "CREDENTIAL", "CREDENTIALS", "KEY", "KEYS", "APIKEY", "AUTH", "AUTHORIZATION",
            "SESSION", "COOKIE", "SIGNATURE", "CERT", "PRIVATE", "SALT", "NONCE",
            "PAT", "DSN", "SID", "BEARER");

    /**
     * Names that match a sensitive segment but are known not to carry a credential. Kept short on
     * purpose: every entry is a deliberate decision that this value is safe to print.
     */
    private static final Set<String> BENIGN_NAMES = Set.of(
            "SSH_AUTH_SOCK",
            "SESSION_MANAGER",
            "DBUS_SESSION_BUS_ADDRESS",
            "XDG_SESSION_TYPE",
            "XDG_SESSION_ID",
            "XDG_SESSION_CLASS",
            "XDG_SESSION_DESKTOP",
            "KDE_SESSION_VERSION",
            "KDE_SESSION_UID",
            "GNOME_KEYRING_CONTROL");

    /** Literal prefixes of well-known credential formats. */
    private static final List<String> CREDENTIAL_PREFIXES = List.of(
            "ghp_", "gho_", "ghu_", "ghs_", "ghr_", "github_pat_",
            "xoxb-", "xoxa-", "xoxp-", "xoxr-", "xoxs-",
            "sk-", "AKIA", "ASIA", "eyJ", "-----BEGIN");

    /**
     * A URL carrying inline credentials, such as a {@code DATABASE_URL} or a Sentry DSN. Matching
     * the shape covers those names without having to enumerate them.
     */
    private static final Pattern CREDENTIALED_URL =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*://[^/\\s:@]+:[^/\\s@]+@");

    private ElideSecretPolicy() {
    }

    /**
     * @return whether the value of this environment variable must be redacted from diagnostics
     */
    static boolean isSensitive(String name, String value) {
        if (name == null || value == null || value.isEmpty()) {
            return false;
        }
        // Shape is checked first, so a credential is caught even under a name on the benign list.
        if (looksLikeCredential(value)) {
            return true;
        }
        if (value.getBytes(StandardCharsets.UTF_8).length < MIN_VALUE_BYTES) {
            return false;
        }
        String upperCaseName = name.toUpperCase(Locale.ROOT);
        if (BENIGN_NAMES.contains(upperCaseName)) {
            return false;
        }
        return hasSensitiveSegment(upperCaseName);
    }

    private static boolean looksLikeCredential(String value) {
        for (String prefix : CREDENTIAL_PREFIXES) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return CREDENTIALED_URL.matcher(value).find();
    }

    private static boolean hasSensitiveSegment(String upperCaseName) {
        for (String segment : upperCaseName.split("[_\\-.]+")) {
            if (SENSITIVE_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        return false;
    }
}
