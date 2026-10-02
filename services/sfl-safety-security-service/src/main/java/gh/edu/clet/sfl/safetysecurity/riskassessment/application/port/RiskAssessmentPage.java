package gh.edu.clet.sfl.safetysecurity.riskassessment.application.port;

import java.util.List;
import java.util.function.Function;

/** One page of an S165 collection, with the totals a client needs to know it does not hold the whole register. */
public record RiskAssessmentPage<T>(List<T> content, int page, int size, long totalElements, int totalPages,
        String sort) {

    public static <T> RiskAssessmentPage<T> of(List<T> content, Paging paging, long totalElements) {
        int pages = paging.size() <= 0 ? 0 : (int) Math.ceil((double) totalElements / paging.size());
        return new RiskAssessmentPage<>(List.copyOf(content), paging.page(), paging.size(), totalElements, pages,
                paging.sort());
    }

    public <R> RiskAssessmentPage<R> map(Function<T, R> mapper) {
        return new RiskAssessmentPage<>(content.stream().map(mapper).toList(), page, size, totalElements,
                totalPages, sort);
    }
}
