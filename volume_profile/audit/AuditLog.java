package volume_profile.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Append-only journal of everything the product decides and does: one file per day,
 * {@code <MotiveWave data dir>/VolumeProfile/audit-YYYY-MM-DD.log}. Never throws - a full disk
 * or a locked file must not break the chart.
 */
public final class AuditLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static volatile Path dirOverride;

    private AuditLog() { }

    /** Tests only: write somewhere else than the user's MotiveWave folder. */
    public static void setDirectory(Path dir) { dirOverride = dir; }

    public static Path directory() {
        if (dirOverride != null) return dirOverride;
        Path home = Path.of(System.getProperty("user.home", "."));
        Path mw = home.resolve("Library").resolve("MotiveWave");          // macOS data dir
        return (Files.isDirectory(mw) ? mw : home).resolve("VolumeProfile");
    }

    public static Path file() {
        return directory().resolve("audit-" + LocalDate.now() + ".log");
    }

    /** One line: time, category (PROBE, LIFECYCLE, BOX, ...), free text. */
    public static synchronized void log(String category, String text) {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            String line = LocalDateTime.now().format(TS) + "  " + category + "  "
                    + text.replace('\n', ' ') + System.lineSeparator();
            Files.writeString(f, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) { }
    }
}
