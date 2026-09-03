package dev.elide.gradle;

import java.util.Locale;

/** A normalized operating-system and architecture pair supported by Elide. */
public record ElidePlatform(String os, String arch, String archiveExtension, String executableName) {
    public static ElidePlatform detect(String osName, String architecture) {
        String os = osName.toLowerCase(Locale.ROOT);
        String normalizedOs = os.equals("linux") ? "linux"
                : os.equals("mac os x") ? "macos"
                : os.startsWith("windows") ? "windows"
                : throwUnsupported(osName, architecture);

        String arch = switch (architecture.toLowerCase(Locale.ROOT)) {
            case "x86_64", "amd64" -> "amd64";
            case "arm64", "aarch64" -> "arm64";
            default -> throwUnsupported(osName, architecture);
        };

        if (normalizedOs.equals("windows") && arch.equals("arm64")) {
            return throwUnsupported(osName, architecture);
        }

        return new ElidePlatform(
                normalizedOs,
                arch,
                normalizedOs.equals("windows") ? "zip" : "tgz",
                normalizedOs.equals("windows") ? "elide.exe" : "elide");
    }

    private static <T> T throwUnsupported(String os, String arch) {
        throw new IllegalArgumentException("Unsupported Elide platform: " + os + "/" + arch);
    }

    public String key() {
        return os + "-" + arch;
    }

    public String assetName() {
        return "elide." + key() + "." + archiveExtension;
    }
}
