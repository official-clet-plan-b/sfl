package gh.edu.clet.sfl.safetysecurity.riskassessment.api;

import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.RiskAssessmentPage;
import java.util.List;

/**
 * The S165 collection envelope, identical in shape to {@code EmergencyPageResponse} and the fleet
 * {@code PageResponse}, so the dashboard's paginated table reads it unchanged.
 */
public record RiskAssessmentPageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages,
        boolean first, boolean last, String sort) {

    public static <T> RiskAssessmentPageResponse<T> of(RiskAssessmentPage<T> page) {
        return new RiskAssessmentPageResponse<>(page.content(), page.page(), page.size(), page.totalElements(),
                page.totalPages(), page.page() == 0, page.page() >= page.totalPages() - 1, page.sort());
    }
}
