package gh.edu.clet.sfl.safetysecurity.drill.application.port;

import java.util.List;

/** One page of an S175 collection, with the totals a client needs to know it does not hold the whole register. */
public record DrillPage<T>(List<T> content, int page, int size, long totalElements, int totalPages, String sort) {

    public static <T> DrillPage<T> of(List<T> content, Paging paging, long totalElements) {
        int pages = paging.size() <= 0 ? 0 : (int) Math.ceil((double) totalElements / paging.size());
        return new DrillPage<>(List.copyOf(content), paging.page(), paging.size(), totalElements, pages,
                paging.sort());
    }
}
