package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A request to carry out one piece of high-risk work, and where it stands. The assessment fields are the S165 record as
 * it stood when the permit was last checked against it - a snapshot, so the evidence says what was relied on.
 *
 * @param locationCode an S152 location reference, held by value
 * @param zoneId an S160a zone, checked in-process when named; null if the work has no zone
 * @param approvalRound 1 for the issue; each resumption opens the next round
 * @param contractorReference the S133 vendor or S176 contractor the work is done for - recorded, never verified from here
 */
public record Permit(UUID id, String siteCode, String reference, UUID permitTypeId, String workType, String title,
        String workDescription, String locationCode, UUID zoneId, String zoneCode, Instant startsAt, Instant endsAt,
        PermitStatus status, String statusReason, UUID riskAssessmentId, String riskAssessmentReference,
        Integer riskAssessmentVersion, PermitRiskLevel riskLevel, Instant riskReviewDueAt, String contractorReference,
        String supervisorReference, String supervisorContact, OriginSystem originSystem, String originReference,
        int approvalRound, String requestedBy, Instant submittedAt, Instant issuedAt, String completionStatement,
        Instant workCompletedAt, String workCompletedBy, Instant closedAt, String closedBy, String createdBy,
        Instant createdAt, Instant updatedAt, long version) {

    /** Issued, not closed, and past its validity window - the overdue close-out the dashboard lists. */
    public boolean overdue(Instant now) {
        return status.open() && endsAt.isBefore(now);
    }

    /** Work may proceed: active, and inside the validity window. */
    public boolean validAt(Instant at) {
        return status == PermitStatus.ACTIVE && !at.isBefore(startsAt) && at.isBefore(endsAt);
    }

    /** A copy under change: start from this permit, set what moved, build. Keeps each transition to the fields it touches. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private UUID id;
        private String siteCode;
        private String reference;
        private UUID permitTypeId;
        private String workType;
        private String title;
        private String workDescription;
        private String locationCode;
        private UUID zoneId;
        private String zoneCode;
        private Instant startsAt;
        private Instant endsAt;
        private PermitStatus status;
        private String statusReason;
        private UUID riskAssessmentId;
        private String riskAssessmentReference;
        private Integer riskAssessmentVersion;
        private PermitRiskLevel riskLevel;
        private Instant riskReviewDueAt;
        private String contractorReference;
        private String supervisorReference;
        private String supervisorContact;
        private OriginSystem originSystem;
        private String originReference;
        private int approvalRound;
        private String requestedBy;
        private Instant submittedAt;
        private Instant issuedAt;
        private String completionStatement;
        private Instant workCompletedAt;
        private String workCompletedBy;
        private Instant closedAt;
        private String closedBy;
        private String createdBy;
        private Instant createdAt;
        private Instant updatedAt;
        private long version;

        private Builder(Permit p) {
            this.id = p.id;
            this.siteCode = p.siteCode;
            this.reference = p.reference;
            this.permitTypeId = p.permitTypeId;
            this.workType = p.workType;
            this.title = p.title;
            this.workDescription = p.workDescription;
            this.locationCode = p.locationCode;
            this.zoneId = p.zoneId;
            this.zoneCode = p.zoneCode;
            this.startsAt = p.startsAt;
            this.endsAt = p.endsAt;
            this.status = p.status;
            this.statusReason = p.statusReason;
            this.riskAssessmentId = p.riskAssessmentId;
            this.riskAssessmentReference = p.riskAssessmentReference;
            this.riskAssessmentVersion = p.riskAssessmentVersion;
            this.riskLevel = p.riskLevel;
            this.riskReviewDueAt = p.riskReviewDueAt;
            this.contractorReference = p.contractorReference;
            this.supervisorReference = p.supervisorReference;
            this.supervisorContact = p.supervisorContact;
            this.originSystem = p.originSystem;
            this.originReference = p.originReference;
            this.approvalRound = p.approvalRound;
            this.requestedBy = p.requestedBy;
            this.submittedAt = p.submittedAt;
            this.issuedAt = p.issuedAt;
            this.completionStatement = p.completionStatement;
            this.workCompletedAt = p.workCompletedAt;
            this.workCompletedBy = p.workCompletedBy;
            this.closedAt = p.closedAt;
            this.closedBy = p.closedBy;
            this.createdBy = p.createdBy;
            this.createdAt = p.createdAt;
            this.updatedAt = p.updatedAt;
            this.version = p.version;
        }

        public Builder id(UUID value) {
            this.id = value;
            return this;
        }

        public Builder siteCode(String value) {
            this.siteCode = value;
            return this;
        }

        public Builder reference(String value) {
            this.reference = value;
            return this;
        }

        public Builder permitTypeId(UUID value) {
            this.permitTypeId = value;
            return this;
        }

        public Builder workType(String value) {
            this.workType = value;
            return this;
        }

        public Builder title(String value) {
            this.title = value;
            return this;
        }

        public Builder workDescription(String value) {
            this.workDescription = value;
            return this;
        }

        public Builder locationCode(String value) {
            this.locationCode = value;
            return this;
        }

        public Builder zoneId(UUID value) {
            this.zoneId = value;
            return this;
        }

        public Builder zoneCode(String value) {
            this.zoneCode = value;
            return this;
        }

        public Builder startsAt(Instant value) {
            this.startsAt = value;
            return this;
        }

        public Builder endsAt(Instant value) {
            this.endsAt = value;
            return this;
        }

        public Builder status(PermitStatus value) {
            this.status = value;
            return this;
        }

        public Builder statusReason(String value) {
            this.statusReason = value;
            return this;
        }

        public Builder riskAssessmentId(UUID value) {
            this.riskAssessmentId = value;
            return this;
        }

        public Builder riskAssessmentReference(String value) {
            this.riskAssessmentReference = value;
            return this;
        }

        public Builder riskAssessmentVersion(Integer value) {
            this.riskAssessmentVersion = value;
            return this;
        }

        public Builder riskLevel(PermitRiskLevel value) {
            this.riskLevel = value;
            return this;
        }

        public Builder riskReviewDueAt(Instant value) {
            this.riskReviewDueAt = value;
            return this;
        }

        public Builder contractorReference(String value) {
            this.contractorReference = value;
            return this;
        }

        public Builder supervisorReference(String value) {
            this.supervisorReference = value;
            return this;
        }

        public Builder supervisorContact(String value) {
            this.supervisorContact = value;
            return this;
        }

        public Builder originSystem(OriginSystem value) {
            this.originSystem = value;
            return this;
        }

        public Builder originReference(String value) {
            this.originReference = value;
            return this;
        }

        public Builder approvalRound(int value) {
            this.approvalRound = value;
            return this;
        }

        public Builder requestedBy(String value) {
            this.requestedBy = value;
            return this;
        }

        public Builder submittedAt(Instant value) {
            this.submittedAt = value;
            return this;
        }

        public Builder issuedAt(Instant value) {
            this.issuedAt = value;
            return this;
        }

        public Builder completionStatement(String value) {
            this.completionStatement = value;
            return this;
        }

        public Builder workCompletedAt(Instant value) {
            this.workCompletedAt = value;
            return this;
        }

        public Builder workCompletedBy(String value) {
            this.workCompletedBy = value;
            return this;
        }

        public Builder closedAt(Instant value) {
            this.closedAt = value;
            return this;
        }

        public Builder closedBy(String value) {
            this.closedBy = value;
            return this;
        }

        public Builder createdBy(String value) {
            this.createdBy = value;
            return this;
        }

        public Builder createdAt(Instant value) {
            this.createdAt = value;
            return this;
        }

        public Builder updatedAt(Instant value) {
            this.updatedAt = value;
            return this;
        }

        public Builder version(long value) {
            this.version = value;
            return this;
        }

        public Permit build() {
            return new Permit(id, siteCode, reference, permitTypeId, workType, title, workDescription, locationCode, zoneId, zoneCode, startsAt, endsAt, status, statusReason, riskAssessmentId, riskAssessmentReference, riskAssessmentVersion, riskLevel, riskReviewDueAt, contractorReference, supervisorReference, supervisorContact, originSystem, originReference, approvalRound, requestedBy, submittedAt, issuedAt, completionStatement, workCompletedAt, workCompletedBy, closedAt, closedBy, createdBy, createdAt, updatedAt, version);
        }
    }
}
