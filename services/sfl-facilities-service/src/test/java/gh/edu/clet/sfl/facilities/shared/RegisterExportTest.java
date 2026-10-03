package gh.edu.clet.sfl.facilities.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gh.edu.clet.sfl.facilities.shared.application.RegisterExport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegisterExportTest {

    private static final Instant AT = Instant.parse("2026-10-03T10:00:00Z");

    @Test
    @DisplayName("a file carries a watermark with who, when, which site and why, and a header row")
    void watermark() {
        RegisterExport.Result r = RegisterExport.build("S177", "agreements", "CLET-HQ", "director", "Quarterly audit", AT, List.of("A", "B"),
                List.of("x"), v -> List.of(v, 1));

        assertThat(r.csv()).startsWith("# CLET S177 agreements - CONFIDENTIAL");
        assertThat(r.csv()).contains("# Exported by director at 2026-10-03T10:00:00Z for site CLET-HQ").contains("# Reason: Quarterly audit").contains("A,B\nx,1\n");
        assertThat(r.fileName()).isEqualTo("s177-agreements-clet-hq-2026-10-03.csv");
    }

    @Test
    @DisplayName("a cell a spreadsheet would run as a formula is neutralised, and commas, quotes and newlines are escaped")
    void cells() {
        RegisterExport.Result r = RegisterExport.build("S170", "findings", "S", "a", "A valid reason", AT, List.of("H"),
                List.of("=HYPERLINK(\"x\")", "+1", "-1", "@cmd", "a,b", "say \"hi\"", "line\nbreak"), v -> List.of(v));

        assertThat(r.csv()).contains("\n\"'=HYPERLINK(\"\"x\"\")\"\n");
        assertThat(r.csv()).contains("\n'+1\n").contains("\n'-1\n").contains("\n'@cmd\n").contains("\n\"a,b\"\n").contains("\n\"say \"\"hi\"\"\"\n").contains("\n\"line\nbreak\"\n");
    }

    @Test
    @DisplayName("a reason of fewer than ten characters is refused")
    void reason() {
        assertThatThrownBy(() -> RegisterExport.reason("audit")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegisterExport.reason(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(RegisterExport.reason("  Statutory audit  ")).isEqualTo("Statutory audit");
    }

    @Test
    @DisplayName("an export over the cap is cut and says so")
    void cap() {
        List<Integer> many = new ArrayList<>();
        for (int i = 0; i < RegisterExport.MAX_ROWS + 3; i++) {
            many.add(i);
        }

        RegisterExport.Result r = RegisterExport.build("S178", "collections", "S", "a", "A valid reason", AT, List.of("N"), many, v -> List.of(v));

        assertThat(r.rows()).isEqualTo(RegisterExport.MAX_ROWS);
        assertThat(r.truncated()).isTrue();
        assertThat(r.csv()).contains("# Truncated at " + RegisterExport.MAX_ROWS + " rows");
    }

    @Test
    @DisplayName("collect reads pages until one is empty")
    void collect() {
        List<Integer> all = RegisterExport.collect(page -> page < 3 ? List.of(page * 2, page * 2 + 1) : List.of());

        assertThat(all).containsExactly(0, 1, 2, 3, 4, 5);
    }
}
