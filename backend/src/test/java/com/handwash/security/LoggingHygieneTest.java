package com.handwash.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ASVS V7 (logging): credentials, tokens and secrets must never reach a log line. This reads the source,
 * extracts every logger call (even multi-line) and every OpsAlert detail, and fails on sensitive words.
 */
class LoggingHygieneTest {
    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern LOG_CALL = Pattern.compile("\\blog\\.(trace|debug|info|warn|error)\\((.*?)\\);", Pattern.DOTALL);
    private static final Pattern OPS_CALL = Pattern.compile("OpsAlert\\.raise\\((.*?)\\);", Pattern.DOTALL);
    private static final Pattern SENSITIVE = Pattern.compile("(?i)(token|secret|password|passwd|credential|authorization|bearer|apikey|api-key|privatekey|x-session|ticket)");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void noLoggerCallMentionsACredential() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            String text = Files.readString(file);
            for (var matcher : List.of(LOG_CALL, OPS_CALL)) {
                var found = matcher.matcher(text);
                while (found.find()) {
                    String call = found.group();
                    if (SENSITIVE.matcher(call).find()) offenders.add(file + ": " + call.replaceAll("\\s+", " "));
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Log calls that mention sensitive words:\n" + String.join("\n", offenders));
    }

    @Test
    void productionCodeNeverPrintsToStdoutOrDumpsStackTraces() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            // Command-line tools (run by CI, never by the server) legitimately print their result.
            if (file.toString().contains("/com/handwash/tools/")) continue;
            String text = Files.readString(file);
            if (text.contains("System.out.") || text.contains("System.err.") || text.contains(".printStackTrace(")) offenders.add(file.toString());
        }
        assertTrue(offenders.isEmpty(), "Direct console output in: " + offenders);
    }

    @Test
    void theScanActuallySeesTheLoggerCalls() throws IOException {
        long calls = 0;
        for (Path file : sources()) {
            var found = LOG_CALL.matcher(Files.readString(file));
            while (found.find()) calls++;
        }
        assertTrue(calls >= 30, "the pattern must match the real logging calls, found " + calls);
    }
}
