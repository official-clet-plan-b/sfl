package gh.edu.clet.sfl.safetysecurity.riskassessment.application.port;

/**
 * Page request for S165 collections - the same normalisation every SFL collection applies (see
 * {@code EmergencyRepository.Paging}), own copy per module.
 */
public record Paging(int page, int size, String sort) {

    public static final int MAX_SIZE = 200;

    public Paging {
        page = Math.max(page, 0);
        size = size <= 0 ? 25 : Math.min(size, MAX_SIZE);
    }

    public int offset() {
        return page * size;
    }
}
