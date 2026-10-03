package gh.edu.clet.sfl.safetysecurity.drill.application.port;

/** Page request for S175 collections - the same normalisation as every SFL collection, own copy per module. */
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
