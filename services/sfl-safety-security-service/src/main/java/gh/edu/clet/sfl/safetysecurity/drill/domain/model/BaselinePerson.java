package gh.edu.clet.sfl.safetysecurity.drill.domain.model;

import java.util.Objects;

/**
 * Someone on site when the drill started, per S160 and S160a - SRS-SFL-S175-02's expected-participant baseline.
 *
 * @param source {@code VISITOR}, {@code ACCESS_CONTROL} or {@code UNKNOWN}, as S162a reported it
 */
public record BaselinePerson(String personRef, String displayName, String source) {

    public BaselinePerson {
        Objects.requireNonNull(personRef, "personRef is required");
        source = source == null || source.isBlank() ? "UNKNOWN" : source;
    }
}
