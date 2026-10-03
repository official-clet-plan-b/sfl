package gh.edu.clet.sfl.safetysecurity.permit.application.service;

/** Cell formatting for exports: a value a spreadsheet would run as a formula is neutralised, and commas, quotes and newlines are escaped. */
final class PermitCsv {

    private PermitCsv() {
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

    static String line(java.util.List<?> values) {
        return values.stream().map(PermitCsv::cell).collect(java.util.stream.Collectors.joining(","));
    }

    static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ");
    }
}
