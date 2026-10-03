package gh.edu.clet.sfl.safetysecurity.permit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitErrorCode;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitException;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalDecision;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalPurpose;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalStage;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.CompetencyCheck;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.EscalationLevel;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Isolation;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.IsolationKind;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.IsolationStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.OriginSystem;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitApproval;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitRiskLevel;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitType;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitWorker;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.RiskAssessmentStanding;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.WorkRole;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.ApprovalPolicy;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.ClosurePolicy;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.CompetencyPolicy;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.ExpiryPolicy;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.ExtensionPolicy;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.PermitPermissionMatrix;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.PermitTypePolicy;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.SubmissionPolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** S164's rules with no database: what each policy refuses, and the name it gives. */
class PermitDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");
    private static final UUID TYPE_ID = UUID.randomUUID();

    private static PermitType hotWork(boolean twoStage) {
        return new PermitType(TYPE_ID, "HOT_WORK", "Hot work", null, PermitRiskLevel.HIGH, "HOT_WORK", true, twoStage, true, 8, List.of("HOT_WORK_OPERATIVE", "FIRE_WATCH"), true, "system", NOW, NOW, 0);
    }

    private static RiskAssessmentStanding current() {
        return new RiskAssessmentStanding(UUID.randomUUID(), true, "RA-1", 1, "HOT_WORK", PermitRiskLevel.LOW, NOW.plus(Duration.ofDays(30)), true, null);
    }

    private static Permit permit(PermitStatus status, String requester) {
        return new Permit(UUID.randomUUID(), "SITE", "PTW-000001", TYPE_ID, "HOT_WORK", "Weld", "Weld", "Plant room", null, null, NOW.plusSeconds(3600), NOW.plusSeconds(18000), status, null,
                UUID.randomUUID(), "RA-1", 1, PermitRiskLevel.LOW, NOW.plus(Duration.ofDays(30)), "ACME", "sup", null, OriginSystem.NONE, null, 1, requester, NOW, null, null, null, null, null, null,
                requester, NOW, NOW, 0);
    }

    private static PermitApproval approval(ApprovalStage stage, String by) {
        return new PermitApproval(UUID.randomUUID(), UUID.randomUUID(), "SITE", ApprovalPurpose.ISSUE, null, 1, stage, ApprovalDecision.APPROVED, by, NOW, null, null);
    }

    private static Isolation isolation(IsolationStatus status) {
        return new Isolation(UUID.randomUUID(), UUID.randomUUID(), "SITE", IsolationKind.ELECTRICAL, "Supply", null, status, status == IsolationStatus.REQUIRED ? null : "v", status == IsolationStatus.REQUIRED ? null : NOW,
                null, status == IsolationStatus.REMOVED ? "r" : null, status == IsolationStatus.REMOVED ? NOW : null, null, NOW);
    }

    private static PermitErrorCode refused(Runnable action) {
        try {
            action.run();
        } catch (PermitException e) {
            return e.errorCode();
        }
        return null;
    }

    @Test
    @DisplayName("S164-01: no assessment, a lapsed one and a mismatched one are each refused under their own name; a current one passes")
    void risk_assessment_rules() {
        PermitType type = hotWork(true);
        Instant starts = NOW.plusSeconds(3600);
        Instant ends = NOW.plusSeconds(7200);

        assertThat(refused(() -> SubmissionPolicy.require(type, starts, ends, 1, 1, null))).isEqualTo(PermitErrorCode.PERMIT_NO_RISK_ASSESSMENT);
        assertThat(refused(() -> SubmissionPolicy.require(type, starts, ends, 1, 1, RiskAssessmentStanding.notLinked()))).isEqualTo(PermitErrorCode.PERMIT_NO_RISK_ASSESSMENT);
        RiskAssessmentStanding lapsed = new RiskAssessmentStanding(UUID.randomUUID(), true, "RA-1", 1, "HOT_WORK", PermitRiskLevel.LOW, NOW.minusSeconds(1), false, "REVIEW_LAPSED");
        assertThat(refused(() -> SubmissionPolicy.require(type, starts, ends, 1, 1, lapsed))).isEqualTo(PermitErrorCode.PERMIT_RISK_ASSESSMENT_NOT_CURRENT);
        RiskAssessmentStanding other = new RiskAssessmentStanding(UUID.randomUUID(), true, "RA-2", 1, "WORKING_AT_HEIGHT", PermitRiskLevel.LOW, NOW.plus(Duration.ofDays(30)), true, null);
        assertThat(refused(() -> SubmissionPolicy.require(type, starts, ends, 1, 1, other))).isEqualTo(PermitErrorCode.PERMIT_RISK_ASSESSMENT_MISMATCH);
        assertThat(refused(() -> SubmissionPolicy.require(type, starts, ends, 1, 1, current()))).isNull();
    }

    @Test
    @DisplayName("S164-01: workers, isolations, a sensible window and an active type are all required")
    void request_completeness() {
        PermitType type = hotWork(true);
        Instant s = NOW.plusSeconds(3600);

        assertThat(refused(() -> SubmissionPolicy.require(type, s, s.plusSeconds(60), 0, 1, current()))).isEqualTo(PermitErrorCode.PERMIT_WORKERS_REQUIRED);
        assertThat(refused(() -> SubmissionPolicy.require(type, s, s.plusSeconds(60), 1, 0, current()))).isEqualTo(PermitErrorCode.PERMIT_ISOLATIONS_REQUIRED);
        assertThat(refused(() -> SubmissionPolicy.require(type, s, s.minusSeconds(1), 1, 1, current()))).isEqualTo(PermitErrorCode.PERMIT_WINDOW_INVALID);
        assertThat(refused(() -> SubmissionPolicy.require(type, s, s.plus(Duration.ofHours(9)), 1, 1, current()))).isEqualTo(PermitErrorCode.PERMIT_WINDOW_INVALID);
        PermitType retired = new PermitType(TYPE_ID, "OLD", "Old", null, PermitRiskLevel.LOW, null, false, false, false, 8, List.of(), false, "s", NOW, NOW, 0);
        assertThat(refused(() -> SubmissionPolicy.require(retired, s, s.plusSeconds(60), 1, 0, null))).isEqualTo(PermitErrorCode.PERMIT_TYPE_INACTIVE);
        PermitType noRa = new PermitType(TYPE_ID, "COLD", "Cold", null, PermitRiskLevel.LOW, null, false, false, false, 8, List.of(), true, "s", NOW, NOW, 0);
        assertThat(refused(() -> SubmissionPolicy.require(noRa, s, s.plusSeconds(60), 1, 0, null))).isNull();
    }

    @Test
    @DisplayName("S164-02: stages run in order, a type of higher risk needs both, and one stage is enough for a lower-risk type")
    void stage_order() {
        PermitType two = hotWork(true);
        PermitType one = new PermitType(TYPE_ID, "COLD", "Cold", null, PermitRiskLevel.LOW, null, false, false, false, 8, List.of(), true, "s", NOW, NOW, 0);

        assertThat(ApprovalPolicy.nextStage(two, List.of())).contains(ApprovalStage.ISSUING_AUTHORITY);
        assertThat(ApprovalPolicy.nextStage(two, List.of(approval(ApprovalStage.ISSUING_AUTHORITY, "a")))).contains(ApprovalStage.SAFETY_SIGN_OFF);
        assertThat(ApprovalPolicy.nextStage(two, List.of(approval(ApprovalStage.ISSUING_AUTHORITY, "a"), approval(ApprovalStage.SAFETY_SIGN_OFF, "b")))).isEmpty();
        assertThat(ApprovalPolicy.nextStage(one, List.of(approval(ApprovalStage.ISSUING_AUTHORITY, "a")))).isEmpty();
    }

    @Test
    @DisplayName("S164-02: the requester cannot approve, an unverified permit cannot be approved, stage two is never stage one's person")
    void approval_refusals() {
        PermitType two = hotWork(true);
        List<Isolation> verified = List.of(isolation(IsolationStatus.VERIFIED));

        assertThat(refused(() -> ApprovalPolicy.require(permit(PermitStatus.ISOLATION_VERIFIED, "req"), two, ApprovalStage.ISSUING_AUTHORITY, "req", List.of(), verified)))
                .isEqualTo(PermitErrorCode.PERMIT_SELF_APPROVAL);
        assertThat(refused(() -> ApprovalPolicy.require(permit(PermitStatus.SUBMITTED, "req"), two, ApprovalStage.ISSUING_AUTHORITY, "boss", List.of(), verified)))
                .isEqualTo(PermitErrorCode.PERMIT_ISOLATION_NOT_VERIFIED);
        assertThat(refused(() -> ApprovalPolicy.require(permit(PermitStatus.ISOLATION_VERIFIED, "req"), two, ApprovalStage.ISSUING_AUTHORITY, "boss", List.of(), List.of(isolation(IsolationStatus.REQUIRED)))))
                .isEqualTo(PermitErrorCode.PERMIT_ISOLATION_NOT_VERIFIED);
        assertThat(refused(() -> ApprovalPolicy.require(permit(PermitStatus.STAGE1_APPROVED, "req"), two, ApprovalStage.SAFETY_SIGN_OFF, "boss",
                List.of(approval(ApprovalStage.ISSUING_AUTHORITY, "boss")), verified))).isEqualTo(PermitErrorCode.PERMIT_STAGE_NOT_INDEPENDENT);
        assertThat(refused(() -> ApprovalPolicy.require(permit(PermitStatus.ISOLATION_VERIFIED, "req"), two, ApprovalStage.SAFETY_SIGN_OFF, "x", List.of(), verified)))
                .isEqualTo(PermitErrorCode.PERMIT_STAGE_OUT_OF_ORDER);
        assertThat(refused(() -> ApprovalPolicy.require(permit(PermitStatus.STAGE1_APPROVED, "req"), two, ApprovalStage.SAFETY_SIGN_OFF, "other",
                List.of(approval(ApprovalStage.ISSUING_AUTHORITY, "boss")), verified))).isNull();
        assertThat(refused(() -> ApprovalPolicy.require(permit(PermitStatus.ACTIVE, "req"), two, ApprovalStage.ISSUING_AUTHORITY, "boss", List.of(), verified)))
                .isEqualTo(PermitErrorCode.PERMIT_INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("S164-02: a missing, failed or expired competence check is an exception; a current competent one is not; the latest check wins")
    void competence() {
        PermitType type = hotWork(true);
        PermitWorker worker = new PermitWorker(UUID.randomUUID(), UUID.randomUUID(), "SITE", "W-1", "Esi", WorkRole.OPERATIVE, NOW);
        Instant through = NOW.plus(Duration.ofHours(5));
        java.util.function.BiFunction<String, Boolean, CompetencyCheck> check = (code, ok) -> new CompetencyCheck(UUID.randomUUID(), worker.permitId(), "SITE", worker.id(), code, ok, null,
                LocalDate.of(2030, 1, 1), "v", NOW, null);

        assertThat(CompetencyPolicy.exceptions(type, List.of(worker), List.of(), through)).extracting(e -> e.reason()).containsOnly(CompetencyPolicy.Reason.MISSING).hasSize(2);
        assertThat(CompetencyPolicy.exceptions(type, List.of(worker), List.of(check.apply("HOT_WORK_OPERATIVE", true), check.apply("FIRE_WATCH", false)), through))
                .singleElement().satisfies(e -> {
                    assertThat(e.competency()).isEqualTo("FIRE_WATCH");
                    assertThat(e.reason()).isEqualTo(CompetencyPolicy.Reason.NOT_COMPETENT);
                });
        CompetencyCheck expired = new CompetencyCheck(UUID.randomUUID(), worker.permitId(), "SITE", worker.id(), "FIRE_WATCH", true, null, LocalDate.of(2026, 10, 1), "v", NOW, null);
        assertThat(CompetencyPolicy.exceptions(type, List.of(worker), List.of(check.apply("HOT_WORK_OPERATIVE", true), expired), NOW.plus(Duration.ofDays(2))))
                .singleElement().satisfies(e -> assertThat(e.reason()).isEqualTo(CompetencyPolicy.Reason.EXPIRED));
        assertThat(CompetencyPolicy.exceptions(type, List.of(worker), List.of(check.apply("HOT_WORK_OPERATIVE", true), check.apply("FIRE_WATCH", true)), through)).isEmpty();
    }

    @Test
    @DisplayName("S164-03: an extension must end later, within the type's limit, on an active permit")
    void extension_rules() {
        PermitType type = hotWork(true);
        Permit active = permit(PermitStatus.ACTIVE, "req");

        assertThat(refused(() -> ExtensionPolicy.require(active, type, active.endsAt().plus(Duration.ofHours(2))))).isNull();
        assertThat(refused(() -> ExtensionPolicy.require(active, type, active.endsAt()))).isEqualTo(PermitErrorCode.PERMIT_EXTENSION_INVALID);
        assertThat(refused(() -> ExtensionPolicy.require(active, type, active.endsAt().plus(Duration.ofHours(9))))).isEqualTo(PermitErrorCode.PERMIT_EXTENSION_INVALID);
        assertThat(refused(() -> ExtensionPolicy.require(permit(PermitStatus.SUSPENDED, "req"), type, active.endsAt().plusSeconds(60)))).isEqualTo(PermitErrorCode.PERMIT_INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("S164-04: completion needs a statement and evidence; removal is by someone else; closing needs every removal")
    void closure_rules() {
        Permit active = permit(PermitStatus.ACTIVE, "req");
        Permit complete = permit(PermitStatus.WORK_COMPLETE, "req");

        assertThat(refused(() -> ClosurePolicy.requireCanComplete(active, "done", 0))).isEqualTo(PermitErrorCode.PERMIT_EVIDENCE_REQUIRED);
        assertThat(refused(() -> ClosurePolicy.requireCanComplete(active, " ", 1))).isEqualTo(PermitErrorCode.PERMIT_COMPLETION_STATEMENT_REQUIRED);
        assertThat(refused(() -> ClosurePolicy.requireCanComplete(active, "done", 1))).isNull();
        assertThat(refused(() -> ClosurePolicy.requireCanRemove(complete, isolation(IsolationStatus.VERIFIED), "req"))).isEqualTo(PermitErrorCode.PERMIT_REMOVER_NOT_INDEPENDENT);
        assertThat(refused(() -> ClosurePolicy.requireCanRemove(complete, isolation(IsolationStatus.VERIFIED), "eng"))).isNull();
        assertThat(refused(() -> ClosurePolicy.requireCanRemove(complete, isolation(IsolationStatus.REQUIRED), "eng"))).isEqualTo(PermitErrorCode.PERMIT_INVALID_STATE_TRANSITION);
        assertThat(refused(() -> ClosurePolicy.requireCanClose(complete, List.of(isolation(IsolationStatus.REMOVED), isolation(IsolationStatus.VERIFIED))))).isEqualTo(PermitErrorCode.PERMIT_ISOLATION_REMOVAL_NOT_RECORDED);
        assertThat(refused(() -> ClosurePolicy.requireCanClose(complete, List.of(isolation(IsolationStatus.REMOVED))))).isNull();
        assertThat(refused(() -> ClosurePolicy.requireCanClose(active, List.of()))).isEqualTo(PermitErrorCode.PERMIT_INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("S164-04: escalation is cumulative - nearing, then both once overdue - and only for permits still open")
    void expiry() {
        Permit active = permit(PermitStatus.ACTIVE, "req");
        Duration hour = Duration.ofHours(1);

        assertThat(ExpiryPolicy.owed(active, active.endsAt().minus(Duration.ofHours(3)), hour)).isEmpty();
        assertThat(ExpiryPolicy.owed(active, active.endsAt().minus(Duration.ofMinutes(30)), hour)).containsExactly(EscalationLevel.NEARING_EXPIRY);
        assertThat(ExpiryPolicy.owed(active, active.endsAt().plusSeconds(1), hour)).containsExactly(EscalationLevel.NEARING_EXPIRY, EscalationLevel.OVERDUE);
        assertThat(ExpiryPolicy.owed(permit(PermitStatus.CLOSED, "req"), active.endsAt().plusSeconds(1), hour)).isEmpty();
        assertThat(ExpiryPolicy.owed(permit(PermitStatus.SUBMITTED, "req"), active.endsAt().plusSeconds(1), hour)).isEmpty();
        assertThat(permit(PermitStatus.WORK_COMPLETE, "req").overdue(active.endsAt().plusSeconds(1))).isTrue();
        assertThat(permit(PermitStatus.CLOSED, "req").overdue(active.endsAt().plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("a permit is valid only while ACTIVE and inside its window; a type of high risk must be two-stage")
    void validity_and_types() {
        Permit active = permit(PermitStatus.ACTIVE, "req");
        assertThat(active.validAt(active.startsAt())).isTrue();
        assertThat(active.validAt(active.startsAt().minusSeconds(1))).isFalse();
        assertThat(active.validAt(active.endsAt())).isFalse();
        assertThat(permit(PermitStatus.SUSPENDED, "req").validAt(active.startsAt().plusSeconds(1))).isFalse();

        assertThatThrownBy(() -> PermitTypePolicy.require(PermitRiskLevel.CRITICAL, false, 8)).isInstanceOf(PermitException.class);
        assertThatThrownBy(() -> PermitTypePolicy.require(PermitRiskLevel.LOW, false, 0)).isInstanceOf(PermitException.class);
        PermitTypePolicy.require(PermitRiskLevel.LOW, false, 24);
        PermitTypePolicy.require(PermitRiskLevel.HIGH, true, 8);
    }

    @Test
    @DisplayName("the builder changes only what it is told to; everything else is copied")
    void builder() {
        Permit before = permit(PermitStatus.DRAFT, "req");
        Permit after = before.toBuilder().status(PermitStatus.SUBMITTED).title("New").build();

        assertThat(after.status()).isEqualTo(PermitStatus.SUBMITTED);
        assertThat(after.title()).isEqualTo("New");
        assertThat(after.reference()).isEqualTo(before.reference());
        assertThat(after.requestedBy()).isEqualTo(before.requestedBy());
        assertThat(after.version()).isEqualTo(before.version());
    }

    @Test
    @DisplayName("the matrix: requesters request, competent persons verify, authorisers approve, the safety sign-off is separate, the SOC suspends, assurance roles change nothing")
    void permission_matrix() {
        assertThat(PermitPermissionMatrix.grants(Set.of(SflRole.FACILITIES_MANAGER), SflPermission.PERMIT_REQUEST)).isTrue();
        assertThat(PermitPermissionMatrix.grants(Set.of(SflRole.FACILITIES_MANAGER), SflPermission.PERMIT_APPROVE)).isFalse();
        assertThat(PermitPermissionMatrix.grants(Set.of(SflRole.FACILITIES_ENGINEER), SflPermission.PERMIT_VERIFY_ISOLATION)).isTrue();
        assertThat(PermitPermissionMatrix.grants(Set.of(SflRole.FACILITIES_ENGINEER), SflPermission.PERMIT_APPROVE)).isFalse();
        assertThat(PermitPermissionMatrix.grants(Set.of(SflRole.FACILITIES_DIRECTOR), SflPermission.PERMIT_APPROVE)).isTrue();
        assertThat(PermitPermissionMatrix.grants(Set.of(SflRole.FACILITIES_DIRECTOR), SflPermission.PERMIT_SAFETY_SIGN_OFF)).isFalse();
        assertThat(PermitPermissionMatrix.grants(Set.of(SflRole.SECURITY_DIRECTOR), SflPermission.PERMIT_SAFETY_SIGN_OFF)).isTrue();
        assertThat(PermitPermissionMatrix.grants(Set.of(SflRole.SOC_OPERATOR), SflPermission.PERMIT_SUSPEND)).isTrue();
        assertThat(PermitPermissionMatrix.grants(Set.of(SflRole.SOC_OPERATOR), SflPermission.PERMIT_APPROVE)).isFalse();
        assertThat(PermitPermissionMatrix.grants(Set.of(SflRole.HSE_MANAGER), SflPermission.PERMIT_CONFIGURE)).isTrue();
        assertThat(PermitPermissionMatrix.grants(Set.of(SflRole.FLEET_DRIVER), SflPermission.PERMIT_READ)).isFalse();
        for (SflPermission permission : SflPermission.values()) {
            if (permission.name().startsWith("PERMIT_") && permission != SflPermission.PERMIT_READ && permission != SflPermission.PERMIT_ANALYTICS_READ && permission != SflPermission.PERMIT_EXPORT) {
                assertThat(PermitPermissionMatrix.grants(Set.of(SflRole.COMPLIANCE_OFFICER), permission)).as("compliance officer holds %s", permission).isFalse();
                assertThat(PermitPermissionMatrix.grants(Set.of(SflRole.COMMAND_ROLE), permission)).as("command holds %s", permission).isFalse();
            }
        }
    }
}
