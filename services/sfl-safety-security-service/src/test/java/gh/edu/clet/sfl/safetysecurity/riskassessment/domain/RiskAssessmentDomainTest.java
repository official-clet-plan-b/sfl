package gh.edu.clet.sfl.safetysecurity.riskassessment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency;
import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.Status;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentErrorCode;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentException;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ActivityTypes;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentContent;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentStanding;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentVersion;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ControlMeasure;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ControlType;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Hazard;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.HazardType;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Likelihood;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlag;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlagStatus;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewTrigger;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RiskAssessment;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RiskScore;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Severity;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SourceChannel;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.policy.ReviewSchedulePolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** S165's rules, each as close to an SRS acceptance criterion or error state as a unit test gets. */
class RiskAssessmentDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");

    static Hazard hazard(Likelihood residualLikelihood, Severity residualSeverity, ControlMeasure... controls) {
        return new Hazard(HazardType.HOT_WORK_FIRE, "Sparks near combustible ceiling void", "Contractors",
                new RiskScore(Likelihood.LIKELY, Severity.MAJOR), new RiskScore(residualLikelihood, residualSeverity),
                List.of(controls));
    }

    static ControlMeasure control() {
        return new ControlMeasure(ControlType.ADMINISTRATIVE, "Fire watch for 60 minutes after work stops");
    }

    static AssessmentVersion draft(String author, AssessmentContent content) {
        return AssessmentVersion.draft(UUID.randomUUID(), UUID.randomUUID(), "MAIN", 1, content, author, author, NOW,
                SourceChannel.WEB, "corr");
    }

    @Nested
    @DisplayName("S165-01 authoring and versioning")
    class Authoring {

        @Test
        void the_risk_level_is_the_highest_residual_rating_never_supplied() {
            AssessmentContent content = new AssessmentContent("Roof works", null, List.of(
                    hazard(Likelihood.RARE, Severity.MINOR, control()),
                    hazard(Likelihood.POSSIBLE, Severity.MAJOR, control())));
            // RARE x MINOR = 2 (LOW); POSSIBLE x MAJOR = 12 (HIGH).
            assertThat(content.riskLevel()).isEqualTo(RiskLevel.HIGH);
            assertThat(content.residualScore()).isEqualTo(12);
        }

        @Test
        void hazard_without_control_is_refused_at_publish_and_names_every_uncontrolled_hazard() {
            AssessmentVersion draft = draft("officer-1", new AssessmentContent("Roof works", null, List.of(
                    hazard(Likelihood.RARE, Severity.MINOR, control()),
                    hazard(Likelihood.RARE, Severity.MINOR),
                    hazard(Likelihood.RARE, Severity.MINOR))));

            assertThatThrownBy(() -> draft.publish(365, "officer-1", NOW, SourceChannel.WEB, "c"))
                    .isInstanceOfSatisfying(RiskAssessmentException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(RiskAssessmentErrorCode.RISK_ASSESSMENT_HAZARD_WITHOUT_CONTROL);
                        assertThat(e.getMessage()).startsWith("Hazard Without Control");
                        assertThat((List<?>) e.details().get("hazardsWithoutControl")).hasSize(2);
                    });
        }

        @Test
        void an_assessment_with_no_hazards_cannot_be_published() {
            AssessmentVersion draft = draft("officer-1", new AssessmentContent("Empty", null, List.of()));
            assertThatThrownBy(() -> draft.publish(365, "officer-1", NOW, SourceChannel.WEB, "c"))
                    .isInstanceOfSatisfying(RiskAssessmentException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(RiskAssessmentErrorCode.RISK_ASSESSMENT_NO_HAZARDS));
        }

        @Test
        void a_published_version_is_never_edited_and_a_superseded_one_is_marked_not_current() {
            AssessmentVersion published = draft("officer-1", new AssessmentContent("Roof works", null,
                    List.of(hazard(Likelihood.RARE, Severity.MINOR, control()))))
                    .publish(365, "officer-1", NOW, SourceChannel.WEB, "c");

            assertThatThrownBy(() -> published.edit(published.content(), "officer-1", NOW, SourceChannel.WEB, "c"))
                    .isInstanceOf(RiskAssessmentException.class);

            AssessmentVersion superseded = published.supersede("officer-1", NOW.plusSeconds(60), SourceChannel.WEB,
                    "c");
            assertThat(superseded.status()).isEqualTo(Status.SUPERSEDED);
            assertThat(superseded.content()).isEqualTo(published.content());
            assertThat(superseded.currency(NOW.plusSeconds(120)).reason())
                    .isEqualTo(RiskAssessmentCurrency.Reason.SUPERSEDED);
        }

        @Test
        void scope_is_an_activity_type_a_location_or_both_and_never_neither() {
            assertThatThrownBy(() -> RiskAssessment.create(UUID.randomUUID(), "MAIN", "RA-1", null, " ", null, "T",
                    "officer-1", NOW, SourceChannel.WEB, "c"))
                    .isInstanceOfSatisfying(RiskAssessmentException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(RiskAssessmentErrorCode.RISK_ASSESSMENT_SCOPE_REQUIRED));
            assertThat(RiskAssessment.create(UUID.randomUUID(), "main", "RA-2", "hot work", null, null, "T",
                    "officer-1", NOW, SourceChannel.WEB, "c").activityType()).isEqualTo("HOT_WORK");
        }

        @Test
        void activity_types_from_every_source_normalise_to_one_spelling() {
            assertThat(ActivityTypes.normalise("Hot work")).isEqualTo("HOT_WORK");
            assertThat(ActivityTypes.normalise("hot-work")).isEqualTo("HOT_WORK");
            assertThat(ActivityTypes.normalise(" HOT_WORK ")).isEqualTo("HOT_WORK");
            assertThat(ActivityTypes.normalise("  ")).isNull();
        }
    }

    @Nested
    @DisplayName("S165-02 review cycle and sign-off")
    class Review {

        private AssessmentVersion publishedHigh(String author) {
            return draft(author, new AssessmentContent("Confined space entry", null,
                    List.of(hazard(Likelihood.POSSIBLE, Severity.MAJOR, control())))) // 12: HIGH
                    .publish(90, author, NOW, SourceChannel.WEB, "c");
        }

        @Test
        void a_higher_risk_assessment_is_not_current_until_someone_other_than_the_author_signs_it_off() {
            AssessmentVersion published = publishedHigh("officer-1");
            assertThat(published.riskLevel()).isEqualTo(RiskLevel.HIGH);
            assertThat(AssessmentStanding.of(published.currency(NOW)))
                    .isEqualTo(AssessmentStanding.AWAITING_INDEPENDENT_SIGN_OFF);

            assertThatThrownBy(() -> published.signOff("officer-1", "Officer One", 90, NOW, SourceChannel.WEB, "c"))
                    .isInstanceOfSatisfying(RiskAssessmentException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(RiskAssessmentErrorCode.RISK_ASSESSMENT_INDEPENDENT_REVIEW_REQUIRED));

            AssessmentVersion signed = published.signOff("reviewer-2", "Reviewer Two", 90, NOW, SourceChannel.WEB, "c");
            assertThat(signed.currency(NOW).current()).isTrue();
        }

        @Test
        void a_low_risk_assessment_may_be_renewed_by_its_author() {
            AssessmentVersion published = draft("officer-1", new AssessmentContent("Office move", null,
                    List.of(hazard(Likelihood.RARE, Severity.MINOR, control()))))
                    .publish(365, "officer-1", NOW, SourceChannel.WEB, "c");
            assertThat(published.signOff("officer-1", "Officer One", 365, NOW, SourceChannel.WEB, "c")
                    .currency(NOW).current()).isTrue();
        }

        @Test
        void given_the_review_date_passes_without_sign_off_the_assessment_lapses_and_a_sign_off_renews_it() {
            AssessmentVersion signed = publishedHigh("officer-1")
                    .signOff("reviewer-2", "Reviewer Two", 90, NOW, SourceChannel.WEB, "c");
            Instant due = signed.reviewDueAt();
            assertThat(due).isEqualTo(NOW.plus(Duration.ofDays(90)));

            // Exclusive: lapsed at the due instant itself, not a tick later.
            assertThat(AssessmentStanding.of(signed.currency(due))).isEqualTo(AssessmentStanding.LAPSED);
            assertThat(signed.currency(due.minusSeconds(1)).current()).isTrue();

            AssessmentVersion renewed = signed.signOff("reviewer-3", "Reviewer Three", 90, due.plusSeconds(60),
                    SourceChannel.WEB, "c");
            assertThat(renewed.currency(due.plusSeconds(120)).current()).isTrue();
            assertThat(renewed.reviewReminderSentAt()).isNull();
        }

        @Test
        void the_reminder_window_opens_lead_days_ahead_and_closes_at_the_due_date() {
            Instant due = NOW.plus(Duration.ofDays(30));
            assertThat(ReviewSchedulePolicy.reminderDue(due, 14, NOW)).isFalse();
            assertThat(ReviewSchedulePolicy.reminderDue(due, 14, due.minus(Duration.ofDays(14)))).isTrue();
            assertThat(ReviewSchedulePolicy.reminderDue(due, 14, due)).isFalse();
            assertThat(ReviewSchedulePolicy.lapsed(due, due)).isTrue();
        }
    }

    @Nested
    @DisplayName("S165-04 post-incident review flags")
    class Flags {

        private ReviewFlag flag() {
            RiskAssessment assessment = RiskAssessment.create(UUID.randomUUID(), "MAIN", "RA-1", "HOT_WORK", null, null,
                    "Hot work", "officer-1", NOW, SourceChannel.WEB, "c");
            return ReviewFlag.raise(UUID.randomUUID(), assessment, 1, ReviewTrigger.INCIDENT, UUID.randomUUID().toString(),
                    "INC-1", "Incident INC-1", "reporter", NOW, SourceChannel.SYSTEM, "c");
        }

        @Test
        void a_flag_cannot_be_cleared_without_findings() {
            assertThatThrownBy(() -> flag().complete("  ", "officer-1", NOW, SourceChannel.WEB, "c"))
                    .isInstanceOfSatisfying(RiskAssessmentException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(RiskAssessmentErrorCode.RISK_ASSESSMENT_REVIEW_FINDINGS_REQUIRED);
                        assertThat(e.getMessage()).startsWith("Flag Dismissed Without Review");
                    });
            assertThat(flag().complete("Fire watch was 20 minutes, not 60. Revised.", "officer-1", NOW,
                    SourceChannel.WEB, "c").status()).isEqualTo(ReviewFlagStatus.CLEARED);
        }

        @Test
        void a_deferral_needs_a_named_reason_and_a_future_date_and_reopens_when_the_date_passes() {
            ReviewFlag flag = flag();
            assertThatThrownBy(() -> flag.defer("", NOW.plusSeconds(86400), "officer-1", NOW, SourceChannel.WEB, "c"))
                    .isInstanceOf(RiskAssessmentException.class);
            assertThatThrownBy(() -> flag.defer("Contractor off site", NOW, "officer-1", NOW, SourceChannel.WEB, "c"))
                    .isInstanceOf(RiskAssessmentException.class);

            ReviewFlag deferred = flag.defer("Contractor off site", NOW.plusSeconds(86400), "officer-1", NOW,
                    SourceChannel.WEB, "c");
            assertThat(deferred.deferralExpired(NOW.plusSeconds(3600))).isFalse();
            assertThat(deferred.deferralExpired(NOW.plusSeconds(86400))).isTrue();
            assertThat(deferred.reopen("system", NOW.plusSeconds(86400), "c").status()).isEqualTo(ReviewFlagStatus.OPEN);
        }

        @Test
        void a_cleared_flag_cannot_be_deferred_or_cleared_again() {
            ReviewFlag cleared = flag().complete("Reviewed.", "officer-1", NOW, SourceChannel.WEB, "c");
            assertThatThrownBy(() -> cleared.complete("Again", "officer-1", NOW, SourceChannel.WEB, "c"))
                    .isInstanceOf(RiskAssessmentException.class);
            assertThatThrownBy(() -> cleared.defer("Later", NOW.plusSeconds(60), "officer-1", NOW, SourceChannel.WEB,
                    "c")).isInstanceOf(RiskAssessmentException.class);
        }
    }

    @Test
    void risk_bands_match_s163s_provisional_matrix() {
        assertThat(new RiskScore(Likelihood.UNLIKELY, Severity.MINOR).level()).isEqualTo(RiskLevel.LOW); // 4
        assertThat(new RiskScore(Likelihood.POSSIBLE, Severity.MODERATE).level()).isEqualTo(RiskLevel.MEDIUM); // 9
        assertThat(new RiskScore(Likelihood.POSSIBLE, Severity.CATASTROPHIC).level()).isEqualTo(RiskLevel.HIGH); // 15
        assertThat(new RiskScore(Likelihood.LIKELY, Severity.CATASTROPHIC).level()).isEqualTo(RiskLevel.CRITICAL); // 20
    }
}
