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
 * <p>A value is first judged on its own: one that cannot hold a recoverable secret -- a couple of
 * bytes or a boolean -- is never redacted, because substituting such a fragment
 * replaces it everywhere it occurs and destroys the diagnostic. Otherwise three independent
 * triggers are used, any of which is sufficient. All name matching is case-insensitive, so a
 * lower-case or camelCase name is treated exactly like a shouting one.
 *
 * <ol>
 *   <li><b>Unambiguous words, matched anywhere in the name.</b> Words such as {@code password},
 *       {@code secret}, {@code token} and {@code signing} do not occur innocently, so they are
 *       matched as plain substrings. This is what catches names that glue words together inside a
 *       single segment, which is how Gradle receives credentials from the environment:
 *       {@code ORG_GRADLE_PROJECT_signingPassword}, {@code AWS_SECRETACCESSKEY},
 *       {@code myApiToken}.
 *   <li><b>Ambiguous words, matched as whole name segments.</b> Words such as {@code KEY} and
 *       {@code AUTH} do occur innocently, so the name is split on {@code _}, {@code -} and
 *       {@code .} and a segment must match exactly. This is what keeps {@code KEYBOARD} and
 *       {@code MONKEY} out of it.
 *   <li><b>Value shape.</b> Some names carry credentials without saying so -- {@code GH_PAT},
 *       {@code DATABASE_URL} -- so a value that looks like a credential is redacted whatever it is
 *       called.
 * </ol>
 *
 * <p>The two lists are deliberately asymmetric. Missing a sensitive name leaks a secret, so that
 * list is broad; missing a benign name only mangles a diagnostic, so that list can stay short and
 * be extended as false positives are found.
 */
final class ElideSecretPolicy {
    /**
     * A value this short carries no recoverable secret, and substituting it would replace that
     * fragment everywhere it occurs and destroy the diagnostic outright -- redacting {@code 1}
     * blanks every digit in a compiler error. This is the one length rule worth having: above it,
     * length says nothing about whether a value is a secret, and an earlier six-byte floor meant a
     * five-character {@code API_KEY} was printed verbatim.
     */
    static final int MIN_VALUE_BYTES = 3;

    /**
     * A prefix match needs a payload behind the prefix to be evidence of anything. A value that is
     * merely {@code eyJ} or {@code sk-} is not a token, and real ones are far longer than this.
     */
    private static final int MIN_PREFIXED_CREDENTIAL_CHARS = 12;

    /** Values that state a mode rather than hold a secret, whatever the variable is called. */
    private static final Set<String> NON_SECRET_VALUES = Set.of(
            "TRUE", "FALSE", "YES", "NO", "ON", "OFF", "NONE", "NULL", "AUTO",
            "ENABLED", "DISABLED", "DEFAULT", "ALWAYS", "NEVER");


    /**
     * Words that never appear innocently in a variable name, matched anywhere within it. Segment
     * matching alone misses the names credentials actually arrive under, because those concatenate
     * words inside one segment: {@code ORG_GRADLE_PROJECT_signingPassword} has no {@code PASSWORD}
     * segment, only a {@code SIGNINGPASSWORD} one.
     */
    private static final List<String> SENSITIVE_WORDS = List.of(
            "PASSWORD", "PASSWD", "PASSPHRASE", "SECRET", "TOKEN", "SIGNING",
            "CREDENTIAL", "APIKEY", "ACCESSKEY", "PRIVATEKEY", "AUTHORIZATION");

    /**
     * Words that can occur innocently, so they only count as a whole name segment. {@code KEY} is
     * the motivating case: it must match {@code API_KEY} but not {@code KEYBOARD}.
     */
    private static final Set<String> SENSITIVE_SEGMENTS = Set.of(
            "KEY", "KEYS", "AUTH", "SESSION", "COOKIE", "SIGNATURE", "CERT",
            "PRIVATE", "SALT", "NONCE", "PAT", "DSN", "SID", "BEARER");

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
        // Judged on the value alone, before any name is considered: these cannot be secrets, and
        // substituting them would corrupt far more output than it could ever protect.
        if (cannotBeSecret(value)) {
            return false;
        }
        String upperCaseName = name.toUpperCase(Locale.ROOT);
        if (hasSensitiveWord(upperCaseName)) {
            return true;
        }
        // Shape is checked before the benign list, so a credential is caught even under a name
        // that is otherwise known to be safe to print.
        if (looksLikeCredential(value)) {
            return true;
        }
        if (BENIGN_NAMES.contains(upperCaseName)) {
            return false;
        }
        return hasSensitiveSegment(upperCaseName);
    }

    private static boolean cannotBeSecret(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length < MIN_VALUE_BYTES) {
            return true;
        }
        // Deliberately no exclusion for short numbers: a numeric value under a name that says KEY
        // can be a real credential, and redacting a timeout or a port is a far smaller loss than
        // printing one. Only values that state a mode are excluded.
        return NON_SECRET_VALUES.contains(value.toUpperCase(Locale.ROOT));
    }

    private static boolean looksLikeCredential(String value) {
        if (value.length() >= MIN_PREFIXED_CREDENTIAL_CHARS) {
            for (String prefix : CREDENTIAL_PREFIXES) {
                if (value.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return CREDENTIALED_URL.matcher(value).find();
    }

    private static boolean hasSensitiveWord(String upperCaseName) {
        for (String word : SENSITIVE_WORDS) {
            if (upperCaseName.contains(word)) {
                return true;
            }
        }
        return false;
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
