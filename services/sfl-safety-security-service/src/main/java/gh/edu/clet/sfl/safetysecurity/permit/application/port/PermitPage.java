package gh.edu.clet.sfl.safetysecurity.permit.application.port;

import java.util.List;

/** One page of an S164 collection, with the totals a client needs to know it does not hold the whole register. */
public record PermitPage<T>(List<T> content, int page, int size, long totalElements, int totalPages, String sort) {

    public static <T> PermitPage<T> of(List<T> content, Paging paging, long totalElements) {
        int pages = paging.size() <= 0 ? 0 : (int) Math.ceil((double) totalElements / paging.size());
        return new PermitPage<>(List.copyOf(content), paging.page(), paging.size(), totalElements, pages, paging.sort());
    }
}
