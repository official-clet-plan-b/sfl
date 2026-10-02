package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.persistence;

import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ControlMeasure;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ControlType;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Hazard;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.HazardType;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Likelihood;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RiskScore;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Severity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hazards and their control measures - for assessment versions and for templates - read and written as
 * ordered child rows.
 *
 * <p>JDBC rather than mapped collections because a draft's hazards are replaced wholesale on every edit,
 * and the replacement must delete the old rows <em>before</em> inserting new ones at the same sequence
 * numbers. Hibernate's flush order runs inserts before deletes, which would trip the
 * {@code (version_id, sequence_no)} unique constraint on the first edit. Here the order is the code's.
 * The caller flushes the parent row first, so the foreign key is satisfied.
 */
@Component
public class AssessmentContentJdbcStore {

    private final JdbcTemplate jdbc;

    public AssessmentContentJdbcStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- assessment versions --------------------------------------------------------------------

    /** Replaces a version's hazards and controls. Controls go with their hazard ({@code ON DELETE CASCADE}). */
    void replaceVersionHazards(UUID versionId, String siteCode, List<Hazard> hazards, RecordMetadata metadata) {
        jdbc.update("DELETE FROM safety_security.risk_assessment_hazards WHERE version_id = ?", versionId);
        Timestamp at = Timestamp.from(metadata.lastModifiedAt());
        for (int index = 0; index < hazards.size(); index++) {
            Hazard hazard = hazards.get(index);
            UUID hazardId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO safety_security.risk_assessment_hazards (id, version_id, site_code, sequence_no,
                        hazard_type, description, who_at_risk, inherent_likelihood, inherent_severity,
                        residual_likelihood, residual_severity, created_by, created_at, last_modified_by,
                        last_modified_at, source_channel, correlation_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, hazardId, versionId, siteCode, index + 1, hazard.hazardType().name(), hazard.description(),
                    hazard.whoAtRisk(), hazard.inherentRisk().likelihood().name(),
                    hazard.inherentRisk().severity().name(), hazard.residualRisk().likelihood().name(),
                    hazard.residualRisk().severity().name(), metadata.lastModifiedBy(), at,
                    metadata.lastModifiedBy(), at, metadata.sourceChannel().name(), metadata.correlationId());
            List<ControlMeasure> controls = hazard.controls();
            for (int c = 0; c < controls.size(); c++) {
                jdbc.update("""
                        INSERT INTO safety_security.risk_assessment_controls (id, hazard_id, site_code, sequence_no,
                            control_type, description, created_by, created_at, last_modified_by, last_modified_at,
                            source_channel, correlation_id)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                        """, UUID.randomUUID(), hazardId, siteCode, c + 1, controls.get(c).controlType().name(),
                        controls.get(c).description(), metadata.lastModifiedBy(), at, metadata.lastModifiedBy(), at,
                        metadata.sourceChannel().name(), metadata.correlationId());
            }
        }
    }

    /** Hazards for each of the given versions, in sequence order. A version with none maps to an empty list. */
    Map<UUID, List<Hazard>> versionHazards(List<UUID> versionIds) {
        return load(versionIds, "risk_assessment_hazards", "version_id", "risk_assessment_controls", "hazard_id");
    }

    // ---- templates --------------------------------------------------------------------------------

    void replaceTemplateHazards(UUID templateId, List<Hazard> hazards) {
        jdbc.update("DELETE FROM safety_security.risk_assessment_template_hazards WHERE template_id = ?", templateId);
        for (int index = 0; index < hazards.size(); index++) {
            Hazard hazard = hazards.get(index);
            UUID hazardId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO safety_security.risk_assessment_template_hazards (id, template_id, sequence_no,
                        hazard_type, description, who_at_risk, inherent_likelihood, inherent_severity,
                        residual_likelihood, residual_severity)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, hazardId, templateId, index + 1, hazard.hazardType().name(), hazard.description(),
                    hazard.whoAtRisk(), hazard.inherentRisk().likelihood().name(),
                    hazard.inherentRisk().severity().name(), hazard.residualRisk().likelihood().name(),
                    hazard.residualRisk().severity().name());
            List<ControlMeasure> controls = hazard.controls();
            for (int c = 0; c < controls.size(); c++) {
                jdbc.update("""
                        INSERT INTO safety_security.risk_assessment_template_controls (id, template_hazard_id,
                            sequence_no, control_type, description)
                        VALUES (?,?,?,?,?)
                        """, UUID.randomUUID(), hazardId, c + 1, controls.get(c).controlType().name(),
                        controls.get(c).description());
            }
        }
    }

    Map<UUID, List<Hazard>> templateHazards(List<UUID> templateIds) {
        return load(templateIds, "risk_assessment_template_hazards", "template_id",
                "risk_assessment_template_controls", "template_hazard_id");
    }

    // ---- shared read -----------------------------------------------------------------------------

    /**
     * Two queries for any number of parents - hazards, then their controls - rather than one per hazard.
     * Table and column names are this class's own constants, never caller input.
     */
    private Map<UUID, List<Hazard>> load(List<UUID> parentIds, String hazardTable, String parentColumn,
            String controlTable, String hazardColumn) {
        Map<UUID, List<Hazard>> result = new LinkedHashMap<>();
        parentIds.forEach(id -> result.put(id, new ArrayList<>()));
        if (parentIds.isEmpty()) {
            return result;
        }
        UUID[] parents = parentIds.toArray(UUID[]::new);
        List<HazardRow> hazardRows = jdbc.query("SELECT id, " + parentColumn + " AS parent_id, hazard_type, "
                + "description, who_at_risk, inherent_likelihood, inherent_severity, residual_likelihood, "
                + "residual_severity FROM safety_security." + hazardTable + " WHERE " + parentColumn
                + " = ANY (?) ORDER BY " + parentColumn + ", sequence_no",
                (rs, n) -> HazardRow.read(rs), (Object) parents);
        if (hazardRows.isEmpty()) {
            return result;
        }
        UUID[] hazardIds = hazardRows.stream().map(HazardRow::id).toArray(UUID[]::new);
        Map<UUID, List<ControlMeasure>> controls = new LinkedHashMap<>();
        jdbc.query("SELECT " + hazardColumn + " AS hazard_id, control_type, description FROM safety_security."
                + controlTable + " WHERE " + hazardColumn + " = ANY (?) ORDER BY " + hazardColumn + ", sequence_no",
                rs -> {
                    controls.computeIfAbsent(rs.getObject("hazard_id", UUID.class), k -> new ArrayList<>())
                            .add(new ControlMeasure(ControlType.valueOf(rs.getString("control_type")),
                                    rs.getString("description")));
                }, (Object) hazardIds);
        for (HazardRow row : hazardRows) {
            result.get(row.parentId()).add(new Hazard(row.hazardType(), row.description(), row.whoAtRisk(),
                    row.inherent(), row.residual(), controls.getOrDefault(row.id(), List.of())));
        }
        return result;
    }

    private record HazardRow(UUID id, UUID parentId, HazardType hazardType, String description, String whoAtRisk,
            RiskScore inherent, RiskScore residual) {

        static HazardRow read(ResultSet rs) throws SQLException {
            return new HazardRow(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                    HazardType.valueOf(rs.getString("hazard_type")), rs.getString("description"),
                    rs.getString("who_at_risk"),
                    new RiskScore(Likelihood.valueOf(rs.getString("inherent_likelihood")),
                            Severity.valueOf(rs.getString("inherent_severity"))),
                    new RiskScore(Likelihood.valueOf(rs.getString("residual_likelihood")),
                            Severity.valueOf(rs.getString("residual_severity"))));
        }
    }
}
