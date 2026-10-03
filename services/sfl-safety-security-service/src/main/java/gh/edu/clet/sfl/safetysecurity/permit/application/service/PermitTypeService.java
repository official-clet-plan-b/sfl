package gh.edu.clet.sfl.safetysecurity.permit.application.service;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitRepository;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitException;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitRiskLevel;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitType;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.PermitTypePolicy;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.AuditPort;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SRS-SFL-S164-01: permit types are configurable, each with its own approvals and validity rules. */
@Service
public class PermitTypeService {

    private final PermitRepository repository;
    private final PermitAccessPolicy access;
    private final AuditPort audit;
    private final Clock clock;

    public PermitTypeService(PermitRepository repository, PermitAccessPolicy access, AuditPort audit, Clock clock) {
        this.repository = repository;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    public record TypeDefinition(String code, String name, String description, PermitRiskLevel riskLevel, String activityType,
            boolean riskAssessmentRequired, boolean twoStage, boolean requiresIsolation, int maxValidityHours,
            List<String> requiredCompetencies, boolean active) {
    }

    @Transactional(readOnly = true)
    public List<PermitType> list(boolean activeOnly, Caller caller) {
        access.requirePermission(caller.actor(), SflPermission.PERMIT_READ, "PermitType");
        return repository.types(activeOnly);
    }

    @Transactional
    public PermitType create(TypeDefinition d, Caller caller) {
        access.requirePermission(caller.actor(), SflPermission.PERMIT_CONFIGURE, "PermitType");
        PermitTypePolicy.require(d.riskLevel(), d.twoStage(), d.maxValidityHours());
        String code = normalise(d.code());
        if (repository.findTypeByCode(code).isPresent()) {
            throw PermitException.invalid("A permit type with code " + code + " already exists.");
        }
        Instant now = clock.instant();
        PermitType saved = repository.insertType(new PermitType(UUID.randomUUID(), code, required(d.name(), "name"), blank(d.description()), d.riskLevel(),
                blank(d.activityType()), d.riskAssessmentRequired(), d.twoStage(), d.requiresIsolation(), d.maxValidityHours(),
                clean(d.requiredCompetencies()), true, caller.id(), now, now, 0));
        audit.record(caller.actor(), caller.channel().name(), "*", "PERMIT_TYPE_CREATED", "PermitType", saved.id().toString(), null, saved, null);
        return saved;
    }

    @Transactional
    public PermitType update(UUID id, TypeDefinition d, Long expectedVersion, Caller caller) {
        access.requirePermission(caller.actor(), SflPermission.PERMIT_CONFIGURE, "PermitType");
        PermitType before = repository.findType(id).orElseThrow(() -> PermitException.notFound("PermitType", id));
        if (expectedVersion != null && expectedVersion != before.version()) {
            throw PermitException.conflict();
        }
        PermitTypePolicy.require(d.riskLevel(), d.twoStage(), d.maxValidityHours());
        PermitType after = repository.updateType(new PermitType(id, before.code(), required(d.name(), "name"), blank(d.description()), d.riskLevel(),
                blank(d.activityType()), d.riskAssessmentRequired(), d.twoStage(), d.requiresIsolation(), d.maxValidityHours(),
                clean(d.requiredCompetencies()), d.active(), before.createdBy(), before.createdAt(), clock.instant(), before.version()), before.version());
        audit.record(caller.actor(), caller.channel().name(), "*", "PERMIT_TYPE_UPDATED", "PermitType", id.toString(), before, after, null);
        return after;
    }

    private static String normalise(String code) {
        return required(code, "code").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw PermitException.invalid(field + " is required");
        }
        return value.strip();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).map(v -> v.strip().toUpperCase(Locale.ROOT)).distinct().toList();
    }
}
