package gh.edu.clet.sfl.safetysecurity.drill.application.port;

import gh.edu.clet.sfl.safetysecurity.drill.domain.model.BaselinePerson;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.Drill;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillCorrectiveAction;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillExecution;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillFinding;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillReview;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillStatus;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillType;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.FrequencyRequirement;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.RollCallGap;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** S175 persistence. Every read is also filtered by row-level security on the caller's site scope (V22). */
public interface DrillRepository {

    // ---- drills ---------------------------------------------------------------------------------

    /** Inserts a new drill, or updates one whose stored version is one behind {@code drill}'s. */
    Drill saveDrill(Drill drill);

    Optional<Drill> findDrill(UUID id);

    /**
     * @param sites the caller's sites; null means every site (a cross-site caller)
     * @param from on or after, by scheduled date - the calendar's window
     */
    record DrillQuery(List<String> sites, DrillType drillType, DrillStatus status, Instant from, Instant to,
            String search, Paging paging) {
    }

    DrillPage<Drill> searchDrills(DrillQuery query);

    record CreationKey(UUID drillId, String requestHash) {
    }

    Optional<CreationKey> findCreationKey(String actorId, String key);

    void recordCreationKey(UUID drillId, String key, String requestHash);

    // ---- execution, baseline and gaps -----------------------------------------------------------

    DrillExecution saveExecution(DrillExecution execution);

    Optional<DrillExecution> findExecution(UUID drillId);

    void saveBaseline(UUID drillId, String siteCode, List<BaselinePerson> persons);

    List<BaselinePerson> findBaseline(UUID drillId);

    void saveGaps(List<RollCallGap> gaps);

    RollCallGap saveGap(RollCallGap gap);

    Optional<RollCallGap> findGap(UUID id);

    List<RollCallGap> findGaps(UUID drillId);

    // ---- after-action review --------------------------------------------------------------------

    DrillReview saveReview(DrillReview review);

    Optional<DrillReview> findReview(UUID drillId);

    DrillFinding saveFinding(DrillFinding finding);

    Optional<DrillFinding> findFinding(UUID id);

    List<DrillFinding> findFindings(UUID drillId);

    // ---- corrective actions ---------------------------------------------------------------------

    DrillCorrectiveAction saveCorrectiveAction(DrillCorrectiveAction action);

    Optional<DrillCorrectiveAction> findCorrectiveAction(UUID id);

    List<DrillCorrectiveAction> findCorrectiveActions(UUID drillId);

    /**
     * Corrective-action counts across the caller's sites - the drill half of the shared HSE panel.
     *
     * @param sites null for every site
     */
    CapaCounts capaCounts(List<String> sites, LocalDate today);

    /** @param ageing open actions by days since raised: 0-30, 31-60, 61-90, over 90 */
    record CapaCounts(long open, long overdue, long verified, long cancelled, List<Long> ageing) {
    }

    // ---- frequency compliance -------------------------------------------------------------------

    FrequencyRequirement saveRequirement(FrequencyRequirement requirement);

    Optional<FrequencyRequirement> findRequirement(UUID id);

    Optional<FrequencyRequirement> findRequirement(String siteCode, DrillType drillType);

    /** @param sites null for every site */
    List<FrequencyRequirement> findRequirements(List<String> sites);

    /** When the last drill of this type at this site that counts toward compliance started, if any. */
    Optional<Instant> lastCountedDrillAt(String siteCode, DrillType drillType);

    // ---- dashboard ------------------------------------------------------------------------------

    /** Per-site completion and participation since {@code since}. @param sites null for every site */
    List<SiteDrillStats> siteStats(List<String> sites, Instant since);

    /**
     * @param executed drills started in the window
     * @param reviewed of those, reviewed or closed
     * @param baselineTotal people in the baselines of those drills; {@code checkedInTotal} checked in
     */
    record SiteDrillStats(String siteCode, long planned, long executed, long reviewed, long cancelled,
            long baselineTotal, long checkedInTotal, long gapsTotal, long findingsOpen, long findingsClosed) {
    }
}
