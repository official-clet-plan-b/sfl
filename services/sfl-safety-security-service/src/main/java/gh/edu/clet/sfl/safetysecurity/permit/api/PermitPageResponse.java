package gh.edu.clet.sfl.safetysecurity.permit.api;

import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitPage;
import java.util.List;

/** The S164 collection envelope, identical in shape to {@code RiskAssessmentPageResponse}. */
public record PermitPageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages,
        boolean first, boolean last, String sort) {

    public static <T> PermitPageResponse<T> of(PermitPage<T> page) {
        return new PermitPageResponse<>(page.content(), page.page(), page.size(), page.totalElements(),
                page.totalPages(), page.page() == 0, page.page() >= page.totalPages() - 1, page.sort());
    }
}
