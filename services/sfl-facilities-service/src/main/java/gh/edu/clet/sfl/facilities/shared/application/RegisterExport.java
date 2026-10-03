package gh.edu.clet.sfl.facilities.shared.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * A register as a CSV file, with the controls an export needs (SRS 4.2, NFR-AUD1): it carries a watermark saying who took
 * it, when, for which site and why; it is capped, and says so when the cap cut it short; and every cell that a
 * spreadsheet would run as a formula is neutralised. Deciding who may export, and writing the audit entry, stay with
 * the module - this only shapes the file.
 */
public final class RegisterExport {

    /** Rows an export will carry. A register larger than this is exported by filter, not in one file. */
    public static final int MAX_ROWS = 5_000;
    public static final int MIN_REASON = 10;

    public record Result(String fileName, String csv, int rows, boolean truncated) {
    }

    private RegisterExport() {
    }

    /** The stated reason, or an IllegalArgumentException: an export without one is refused. */
    public static String reason(String reason) {
        String text = reason == null ? "" : reason.strip();
        if (text.length() < MIN_REASON) {
            throw new IllegalArgumentException("An export needs a reason of at least " + MIN_REASON + " characters.");
        }
        return text;
    }

    /** Reads pages (size 100) until the register or the cap runs out. */
    public static <T> List<T> collect(IntFunction<List<T>> page) {
        List<T> all = new ArrayList<>();
        for (int index = 0; all.size() < MAX_ROWS + 1; index++) {
            List<T> items = page.apply(index);
            if (items.isEmpty()) {
                break;
            }
            all.addAll(items);
        }
        return all;
    }

    public static <T> Result build(String system, String register, String site, String actor, String reason, Instant at, List<String> headers,
            List<T> items, Function<T, List<Object>> row) {
        boolean truncated = items.size() > MAX_ROWS;
        List<T> kept = truncated ? items.subList(0, MAX_ROWS) : items;
        StringBuilder out = new StringBuilder();
        out.append("# CLET ").append(system).append(' ').append(register).append(" - CONFIDENTIAL, for the stated purpose only\n");
        out.append("# Exported by ").append(oneLine(actor)).append(" at ").append(at).append(" for site ").append(oneLine(site)).append('\n');
        out.append("# Reason: ").append(oneLine(reason)).append('\n');
        if (truncated) {
            out.append("# Truncated at ").append(MAX_ROWS).append(" rows: narrow the filter to export the rest\n");
        }
        out.append(String.join(",", headers.stream().map(RegisterExport::cell).toList())).append('\n');
        for (T item : kept) {
            out.append(String.join(",", row.apply(item).stream().map(RegisterExport::cell).toList())).append('\n');
        }
        String name = (system + "-" + register + "-" + site + "-" + at.toString().substring(0, 10)).toLowerCase().replaceAll("[^a-z0-9.-]+", "-") + ".csv";
        return new Result(name, out.toString(), kept.size(), truncated);
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ");
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
