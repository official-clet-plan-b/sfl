package gh.edu.clet.sfl.facilities.waste.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.waste.domain.WasteMetrics;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sustainability figures - SRS-SFL-S178-04. Calculated from the collection records, never typed in.
 *
 * <p>Two rules run through every figure. <strong>Measured and estimated never mix:</strong> a percentage is
 * worked out from measured kilograms only, and estimated kilograms are reported beside it, labelled, with
 * the rule that produced them. <strong>The report names its sources:</strong> every line lists the
 * collection references that went into it, so a figure can be traced to the records behind it.
 */
@Service
public class WasteReportService {

    public static final String ESTIMATION_RULE = "Quantities flagged ESTIMATED were entered as an estimate by "
            + "the person recording the collection. They are totalled separately and are excluded from every "
            + "percentage. Units are converted to kilograms with the configured factor and the original entry is "
            + "kept on the collection.";

    public static final String DIVERSION_RULE = "Diversion = measured kilograms of diverting streams delivered to a "
            + "destination that is not landfill, divided by all measured kilograms in the period. A collection with "
            + "no destination yet is not counted as diverted.";

    private final WasteStore store;
    private final WasteSupport support;

    public WasteReportService(WasteStore store, WasteSupport support) {
        this.store = store;
        this.support = support;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(String siteCode, int periodDays, Caller caller) {
        String site = WasteSupport.code(siteCode, "siteCode");
        support.require(caller, SflPermission.FACILITIES_WASTE_READ, site, "WasteDashboard", site);
        int days = Math.min(Math.max(7, periodDays), 366);
        LocalDate today = support.today();
        List<WasteStore.StreamTotals> totals = store.streamTotals(site, today.minusDays(days), today);
        BigDecimal measured = sum(totals, WasteStore.StreamTotals::measuredKg);
        BigDecimal estimated = sum(totals, WasteStore.StreamTotals::estimatedKg);
        BigDecimal diverted = sum(totals, WasteStore.StreamTotals::divertedMeasuredKg);
        long[] certificates = store.certificateCounts(site);
        List<LocalDate> missed = store.missedScheduledDates(site);
        long over14 = missed.stream().filter(d -> ChronoUnit.DAYS.between(d, today) > 14).count();
        long over7 = missed.stream().filter(d -> ChronoUnit.DAYS.between(d, today) > 7).count() - over14;
        long upTo7 = missed.size() - over7 - over14;
        Long oldest = missed.isEmpty() ? null : ChronoUnit.DAYS.between(missed.get(0), today);
        return new Dashboard(site, days, measured, estimated, WasteMetrics.percent(diverted, measured),
                WasteMetrics.percent(certificates[1], certificates[0]), certificates[0], certificates[1],
                new Ageing(missed.size(), upTo7, over7, over14, oldest), store.openHazardousChainExceptions(site),
                store.openExceptionCount(site, today), store.overdueExceptionCount(site, today));
    }

    @Transactional
    public Report report(String siteCode, LocalDate from, LocalDate to, Caller caller) {
        String site = WasteSupport.code(siteCode, "siteCode");
        support.require(caller, SflPermission.FACILITIES_WASTE_READ, site, "WasteReport", site);
        LocalDate end = to == null ? support.today() : to;
        LocalDate start = from == null ? end.minusDays(89) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        List<WasteStore.StreamTotals> lines = store.streamTotals(site, start, end);
        BigDecimal measured = sum(lines, WasteStore.StreamTotals::measuredKg);
        BigDecimal estimated = sum(lines, WasteStore.StreamTotals::estimatedKg);
        BigDecimal diverted = sum(lines, WasteStore.StreamTotals::divertedMeasuredKg);
        // Viewing a report is an auditable read - it is the document handed to a regulator.
        support.audit(caller, AuditAction.WASTE_REPORT_VIEWED, "WasteReport", UUID.nameUUIDFromBytes(site.getBytes()),
                site, null, start + ".." + end);
        return new Report(site, start, end, lines, measured, estimated, WasteMetrics.percent(diverted, measured),
                ESTIMATION_RULE, DIVERSION_RULE, lines.stream().mapToLong(WasteStore.StreamTotals::collections).sum(),
                lines.stream().mapToLong(WasteStore.StreamTotals::estimatedCollections).sum());
    }

    private static BigDecimal sum(List<WasteStore.StreamTotals> totals,
            java.util.function.Function<WasteStore.StreamTotals, BigDecimal> field) {
        return totals.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public record Ageing(long missedOpen, long upTo7Days, long from8To14Days, long over14Days, Long oldestDays) {
    }

    public record Dashboard(String siteCode, int periodDays, BigDecimal measuredKg, BigDecimal estimatedKg,
            BigDecimal diversionRatePercent, BigDecimal certificateCompletionPercent, long handedOverCollections,
            long certifiedCollections, Ageing missedCollections, long openHazardousChainExceptions,
            long openExceptions, long overdueExceptions) {
    }

    public record Report(String siteCode, LocalDate from, LocalDate to, List<WasteStore.StreamTotals> lines,
            BigDecimal measuredKg, BigDecimal estimatedKg, BigDecimal diversionRatePercent, String estimationRule,
            String diversionRule, long collections, long estimatedCollections) {
    }
}
