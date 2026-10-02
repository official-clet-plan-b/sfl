package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

import java.util.Locale;

/**
 * The one spelling of an activity type.
 *
 * <p>S165-03 matches the activity types the platform observes (S176's {@code workTypes}, an incident's
 * activity) against those assessments are scoped to. A match that failed on {@code "Hot work"} versus
 * {@code HOT_WORK} would report a gap that is not one, so every activity type is normalised the same way
 * on the way in, from every source: trimmed, upper-cased, runs of spaces and hyphens to one underscore.
 */
public final class ActivityTypes {

    public static final int MAX_LENGTH = 80;

    private ActivityTypes() {
    }

    /** The canonical form, or null for null/blank. */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String canonical = raw.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s\\-]+", "_")
                .replaceAll("[^A-Z0-9_]", "").replaceAll("_+", "_");
        if (canonical.isEmpty()) {
            return null;
        }
        if (canonical.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("An activity type is at most " + MAX_LENGTH + " characters");
        }
        return canonical;
    }
}
