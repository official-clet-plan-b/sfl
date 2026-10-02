package gh.edu.clet.sfl.safetysecurity.incident.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CorrectiveActionJpaRepository extends JpaRepository<CorrectiveActionJpaEntity, UUID> {

    List<CorrectiveActionJpaEntity> findByIncidentIdOrderByCreatedAtAsc(UUID incidentId);

    /**
     * Backs the SRS D.9 hard-rule-1 closure gate ({@code IncidentClosureService#close}) and is not
     * used anywhere else. Delegates to {@link #lockOpenMandatoryForClosure} rather than running its
     * own {@code COUNT(...)} query directly, because Postgres rejects {@code FOR SHARE}/{@code FOR
     * UPDATE} on a query containing an aggregate function ("FOR UPDATE/SHARE is not allowed with
     * aggregate functions") - the row lock has to be taken on a row-returning query, so the count is
     * derived in Java from the locked rows instead.
     */
    default long countOpenMandatory(UUID incidentId) {
        return lockOpenMandatoryForClosure(incidentId).size();
    }

    /**
     * {@code PESSIMISTIC_READ} makes Postgres issue {@code SELECT ... FOR SHARE} over the mandatory
     * corrective actions for this incident, so a concurrent transaction inserting or updating a
     * mandatory CAPA for the same incident blocks until this transaction commits (or rolls back) -
     * closing the narrow TOCTOU window where a CAPA opened and committed between this read and the
     * closing transaction's own commit would otherwise be invisible under default READ COMMITTED
     * isolation. {@code @Lock} composes with a {@code @Query}-annotated method the same way it does
     * with a derived one - Spring Data wraps the generated query in the requested lock mode.
     */
    /**
     * The HSE dashboard's corrective-action counts for one site - open, overdue, verified, cancelled, then open
     * actions by age (0-30, 31-60, 61-90, over 90 days). One row; read-only, so no lock.
     */
    @Query(nativeQuery = true, value = """
            SELECT count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS')),
                   count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS') AND due_date < :today),
                   count(*) FILTER (WHERE status = 'VERIFIED'),
                   count(*) FILTER (WHERE status = 'CANCELLED'),
                   count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS') AND :today - created_at::date <= 30),
                   count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS')
                       AND :today - created_at::date BETWEEN 31 AND 60),
                   count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS')
                       AND :today - created_at::date BETWEEN 61 AND 90),
                   count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS') AND :today - created_at::date > 90)
              FROM safety_security.corrective_actions
             WHERE site_code = :siteCode
            """)
    List<Object[]> capaCounts(@Param("siteCode") String siteCode, @Param("today") LocalDate today);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            SELECT a FROM CorrectiveActionJpaEntity a
            WHERE a.incidentId = :incidentId AND a.mandatory = true AND a.status IN ('OPEN', 'IN_PROGRESS')
            """)
    List<CorrectiveActionJpaEntity> lockOpenMandatoryForClosure(@Param("incidentId") UUID incidentId);
}
