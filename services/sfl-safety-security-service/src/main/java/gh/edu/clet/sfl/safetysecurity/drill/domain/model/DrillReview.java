package gh.edu.clet.sfl.safetysecurity.drill.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The after-action review's narrative - SRS-SFL-S175-03: "captures timing (notification-to-muster duration),
 * participation gaps and qualitative findings". Timing and gaps are measured, on {@link DrillExecution} and
 * {@link RollCallGap}; this holds what people concluded, and when the review was submitted.
 */
public record DrillReview(UUID drillId, String siteCode, String summary, String timingNotes, String recordedBy,
        Instant recordedAt, String submittedBy, Instant submittedAt) {

    public DrillReview {
        Objects.requireNonNull(drillId, "drillId is required");
        summary = blankToNull(summary);
        timingNotes = blankToNull(timingNotes);
    }

    public DrillReview submitted(String by, Instant at) {
        return new DrillReview(drillId, siteCode, summary, timingNotes, recordedBy, recordedAt, by, at);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
