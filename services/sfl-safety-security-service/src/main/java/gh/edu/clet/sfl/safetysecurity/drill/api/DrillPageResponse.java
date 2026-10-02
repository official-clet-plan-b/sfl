package gh.edu.clet.sfl.safetysecurity.drill.api;

import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillPage;
import java.util.List;

/** The S175 collection envelope, identical in shape to {@code RiskAssessmentPageResponse}. */
public record DrillPageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages,
        boolean first, boolean last, String sort) {

    public static <T> DrillPageResponse<T> of(DrillPage<T> page) {
        return new DrillPageResponse<>(page.content(), page.page(), page.size(), page.totalElements(),
                page.totalPages(), page.page() == 0, page.page() >= page.totalPages() - 1, page.sort());
    }
}
