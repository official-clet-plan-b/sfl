package gh.edu.clet.sfl.safetysecurity.platform.application;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Marks the threads that do the platform's own work, so row-level security can scope them.
 *
 * <p>Ported from {@code sfl-facilities-service}'s {@code PlatformThreads}, which exists because of a
 * defect found there: {@code SiteScopeGuc} sets {@code app.site_scopes} from the actor on the current
 * HTTP request, and a scheduled sweep, the outbox drainer and the broker listener have no request. Under
 * {@code sfl_app} each of them would read zero rows from every policy-carrying table - silently, in the
 * one environment nobody tests. SSEMP gains its first policy-carrying tables in Phase 2 (ADR 0010), so
 * it gains this at the same time rather than rediscovering the defect.
 *
 * <p>The mark is on the thread, not the method, because the GUC is issued when a transaction begins - a
 * scope set at the top of an {@code @Transactional} job body would be set too late. A thread created by
 * {@link #factory} carries the mark from its first instruction.
 *
 * <p>The mark means {@code *}, the cross-site scope: the jobs that run on these threads are service
 * accounts deciding per record what to do. A request thread is never marked, so an HTTP caller can never
 * inherit it.
 */
public final class PlatformThreads {

    private static final ThreadLocal<Boolean> PLATFORM = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private PlatformThreads() {
    }

    /** {@code true} on a scheduler, drainer or broker-listener thread created by {@link #factory}. */
    public static boolean isPlatformThread() {
        return PLATFORM.get();
    }

    /** A thread factory whose threads are platform threads from their first instruction. */
    public static ThreadFactory factory(String namePrefix) {
        AtomicInteger sequence = new AtomicInteger();
        return work -> {
            Thread thread = new Thread(() -> {
                PLATFORM.set(Boolean.TRUE);
                work.run();
            }, namePrefix + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    /**
     * Runs {@code work} as platform work on the current thread, restoring the previous state after.
     *
     * <p>Only for work that is genuinely the platform's rather than the caller's. Anything that starts a
     * transaction inside {@code work} is scoped to {@code *}; a transaction the caller already began is
     * not affected. Never wrap a caller's business action in this - that would widen an HTTP caller to
     * every site.
     */
    public static <T> T callAsPlatform(Supplier<T> work) {
        boolean previous = PLATFORM.get();
        PLATFORM.set(Boolean.TRUE);
        try {
            return work.get();
        } finally {
            PLATFORM.set(previous);
        }
    }
}
