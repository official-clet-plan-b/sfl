package gh.edu.clet.sfl.safetysecurity.riskassessment.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.AuditPort;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.RiskAssessmentRepository;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentException;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentTemplate;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Hazard;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SourceChannel;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The template library S165-01 authors against. Global, not site-scoped - see {@link AssessmentTemplate}.
 * Reading is open to anyone who reads assessments; changing the library is a configuration act.
 */
@Service
public class AssessmentTemplateService {

    private final RiskAssessmentRepository repository;
    private final RiskAssessmentAccessPolicy access;
    private final AuditPort audit;
    private final Clock clock;

    public AssessmentTemplateService(RiskAssessmentRepository repository, RiskAssessmentAccessPolicy access,
            AuditPort audit, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    public record TemplateContent(String name, String activityType, String description, boolean active,
            List<Hazard> hazards) {
    }

    @Transactional(readOnly = true)
    public List<AssessmentTemplate> list(boolean activeOnly, ActorContext actor) {
        access.requirePermission(actor, SflPermission.RISK_ASSESSMENT_READ, "AssessmentTemplate", null);
        return repository.findTemplates(activeOnly);
    }

    @Transactional(readOnly = true)
    public AssessmentTemplate get(UUID id, ActorContext actor) {
        access.requirePermission(actor, SflPermission.RISK_ASSESSMENT_READ, "AssessmentTemplate", id.toString());
        return require(id);
    }

    @Transactional
    public AssessmentTemplate create(TemplateContent content, ActorContext actor, SourceChannel channel) {
        access.requirePermission(actor, SflPermission.RISK_ASSESSMENT_CONFIGURE, "AssessmentTemplate", null);
        Instant now = clock.instant();
        AssessmentTemplate template = repository.saveTemplate(new AssessmentTemplate(UUID.randomUUID(), content.name(),
                content.activityType(), content.description(), true, content.hazards(),
                RecordMetadata.createdBy(actor.actorId(), now, channel, actor.correlationId())));
        audit.record(actor, channel.name(), null, "RISK_ASSESSMENT_TEMPLATE_CREATED", "AssessmentTemplate",
                template.id().toString(), null, template, null);
        return template;
    }

    /** Revises or retires a template. Assessments already copied from it are untouched, by design. */
    @Transactional
    public AssessmentTemplate update(UUID id, TemplateContent content, Long expectedVersion, ActorContext actor,
            SourceChannel channel) {
        access.requirePermission(actor, SflPermission.RISK_ASSESSMENT_CONFIGURE, "AssessmentTemplate", id.toString());
        AssessmentTemplate existing = require(id);
        existing.metadata().requireVersion(expectedVersion);
        AssessmentTemplate revised = repository.saveTemplate(existing.revise(content.name(), content.activityType(),
                content.description(), content.active(), content.hazards(),
                existing.metadata().modifiedBy(actor.actorId(), clock.instant(), channel, actor.correlationId())));
        audit.record(actor, channel.name(), null, "RISK_ASSESSMENT_TEMPLATE_UPDATED", "AssessmentTemplate",
                id.toString(), existing, revised, null);
        return revised;
    }

    private AssessmentTemplate require(UUID id) {
        return repository.findTemplate(id).orElseThrow(() -> RiskAssessmentException.notFound("AssessmentTemplate", id));
    }
}
