package gh.edu.clet.sfl.safetysecurity.emergency.domain.policy;

import gh.edu.clet.sfl.safetysecurity.emergency.domain.exception.EmergencyErrorCode;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.exception.EmergencyException;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.NotificationTemplate;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 2 SRS-SFL-S175-01: "A test-mode notification must never be deliverable through a channel or
 * template indistinguishable from a real alert; the two are visually and structurally distinct at every
 * step." Error state "Test/Real Ambiguity": refused at setup, not caught after the fact.
 *
 * <p>Visually: a drill template's title and body both open with {@link #MARKER}, the first thing any
 * recipient on any channel reads. Structurally: the template is flagged {@code drill}, the activation runs in
 * {@code DRILL} mode, and the gateway is told it is sending a drill. Each check below is applied where the
 * mistake would be made - creating a template, creating a real activation, triggering a drill - so a drill
 * that would look real, or a real alert that would look like a drill, is refused before anything is sent.
 *
 * <p>The marker is a constant, not configuration. A recipient learns one phrase that always means "exercise";
 * a phrase that varied by site or template is one somebody mistakes for real.
 */
public final class DrillSeparationPolicy {

    /** What every drill message opens with, on every channel. */
    public static final String MARKER = "DRILL - THIS IS AN EXERCISE";

    private DrillSeparationPolicy() {
    }

    /** At template creation: a drill template carries the marker up front; a real one carries it nowhere. */
    public static void requireConsistentTemplate(boolean drill, boolean breakGlassEligible, String title, String body) {
        if (drill) {
            if (!opensWithMarker(title) || !opensWithMarker(body)) {
                throw ambiguity("A drill template's title and body must both open with \"" + MARKER + "\".");
            }
            if (breakGlassEligible) {
                throw ambiguity("A drill template cannot be break-glass eligible.");
            }
        } else if (containsMarker(title) || containsMarker(body)) {
            throw ambiguity("A real alert template may not contain \"" + MARKER + "\" - recipients would read it as an"
                    + " exercise.");
        }
    }

    /** A real activation, routine or break-glass, may never be sent with a drill template. */
    public static void requireRealTemplate(NotificationTemplate template) {
        if (template.drill()) {
            throw ambiguity("Template " + template.templateCode() + " is a drill template and cannot send a real alert.");
        }
    }

    /**
     * A drill is only ever sent with a drill template that still carries the marker - rechecked at send, so a
     * template stored before this rule existed cannot slip through.
     */
    public static void requireDrillTemplate(NotificationTemplate template) {
        if (!template.drill() || !opensWithMarker(template.title()) || !opensWithMarker(template.body())) {
            throw ambiguity("Template " + template.templateCode() + " is not a drill template carrying \"" + MARKER
                    + "\"; a drill cannot be sent with it.");
        }
    }

    private static boolean opensWithMarker(String text) {
        return text != null && text.strip().toUpperCase(Locale.ROOT).startsWith(MARKER);
    }

    private static boolean containsMarker(String text) {
        return text != null && text.toUpperCase(Locale.ROOT).contains(MARKER);
    }

    private static EmergencyException ambiguity(String reason) {
        return new EmergencyException(EmergencyErrorCode.EMERGENCY_TEST_REAL_AMBIGUITY, Map.of("reason", reason));
    }
}
