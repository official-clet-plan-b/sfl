package gh.edu.clet.sfl.fleetlogistics.fleet.infrastructure.persistence;

import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DriverEligibilityStatus;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DriverLifecycleStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DriverProfileJpaRepository extends JpaRepository<DriverProfileEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DriverProfileEntity d where d.id = :id")
    Optional<DriverProfileEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select d from DriverProfileEntity d
            where d.siteCode = :siteCode
              and upper(d.staffReference) = upper(:staffReference)
              and d.lifecycleStatus <> gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DriverLifecycleStatus.ARCHIVED
            """)
    Optional<DriverProfileEntity> findActiveByStaffReference(@Param("siteCode") String siteCode,
            @Param("staffReference") String staffReference);

    /**
     * The active profile bound to an identity.
     *
     * <p>Compared exactly, not folded through {@code upper()} like the staff reference beside it: a
     * subject claim is an opaque identifier and case is part of it. Folding it through {@code upper()}
     * would work by luck for one provider's format and silently break the match for another's - the
     * whole point of staying provider-agnostic is not needing to know or care which.
     */
    @Query("""
            select d from DriverProfileEntity d
            where d.principalSubject = :principalSubject
              and d.lifecycleStatus <> gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DriverLifecycleStatus.ARCHIVED
            """)
    Optional<DriverProfileEntity> findActiveByPrincipalSubject(@Param("principalSubject") String principalSubject);

    @Query("""
            select d from DriverProfileEntity d
            where d.siteCode = :siteCode
              and upper(d.licenceNumber) = upper(:licenceNumber)
              and d.lifecycleStatus <> gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DriverLifecycleStatus.ARCHIVED
            """)
    Optional<DriverProfileEntity> findActiveByLicenceNumber(@Param("siteCode") String siteCode,
            @Param("licenceNumber") String licenceNumber);

    /**
     * Paged search.
     *
     * <p>Optional filters use {@code coalesce} rather than {@code :x is null or ...}: PostgreSQL
     * cannot infer the type of a bind parameter that never appears in a typed position - an
     * {@code IS NULL} test, or an argument to {@code upper()} or {@code concat()} - and rejects the
     * prepare with SQLSTATE 42P18. Inside {@code coalesce} the parameter takes its type from the
     * column beside it. Every column used this way is {@code NOT NULL}, so an absent filter still
     * matches every row, exactly as the previous form did.
     */
    // An eligibility view answers "who can I assign?", and an archived driver cannot be assigned
    // whatever their licence says - so they appear under All, never under Eligible.
    @Query("""
            select d from DriverProfileEntity d
            where (:allSites = true or d.siteCode in :siteScopes)
              and (:siteCode is null or d.siteCode = :siteCode)
              and (:lifecycleStatus is null or d.lifecycleStatus = :lifecycleStatus)
              and (:eligibilityStatus is null or d.eligibilityStatus = :eligibilityStatus)
              and (:eligibilityStatus is null
                   or d.lifecycleStatus <> gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DriverLifecycleStatus.ARCHIVED)
              and upper(d.responsibleUnit) = upper(coalesce(:responsibleUnit, d.responsibleUnit))
              and d.licenceExpiresOn <= coalesce(:licenceExpiringBefore, d.licenceExpiresOn)
              and (upper(d.displayName) like upper(concat('%', coalesce(:search, d.displayName), '%'))
                   or upper(d.staffReference)
                      like upper(concat('%', coalesce(:search, d.staffReference), '%')))
            """)
    Page<DriverProfileEntity> search(
            @Param("allSites") boolean allSites,
            @Param("siteScopes") List<String> siteScopes,
            @Param("siteCode") String siteCode,
            @Param("lifecycleStatus") DriverLifecycleStatus lifecycleStatus,
            @Param("eligibilityStatus") DriverEligibilityStatus eligibilityStatus,
            @Param("responsibleUnit") String responsibleUnit,
            @Param("licenceExpiringBefore") LocalDate licenceExpiringBefore,
            @Param("search") String search,
            Pageable pageable);

    @Query("""
            select d from DriverProfileEntity d
            where (:allSites = true or d.siteCode in :siteScopes)
            order by d.siteCode asc, d.displayName asc
            """)
    List<DriverProfileEntity> findAllInScope(@Param("allSites") boolean allSites,
            @Param("siteScopes") List<String> siteScopes);

    @Query("""
            select d from DriverProfileEntity d
            where d.lifecycleStatus <> gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DriverLifecycleStatus.ARCHIVED
              and (d.licenceExpiresOn <= :threshold
                   or (d.medicalClearanceExpiresOn is not null and d.medicalClearanceExpiresOn <= :threshold))
            order by d.licenceExpiresOn asc
            """)
    List<DriverProfileEntity> findExpiringOnOrBefore(@Param("threshold") LocalDate threshold);
}
