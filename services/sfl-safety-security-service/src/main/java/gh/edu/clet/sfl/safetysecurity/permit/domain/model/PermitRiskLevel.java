package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

/** The permit type's risk level. Higher-risk types need two-stage approval (S164-02). Same bands as S165's assessments. */
public enum PermitRiskLevel {
    LOW(false), MEDIUM(false), HIGH(true), CRITICAL(true);

    private final boolean requiresTwoStage;

    PermitRiskLevel(boolean requiresTwoStage) {
        this.requiresTwoStage = requiresTwoStage;
    }

    public boolean requiresTwoStage() {
        return requiresTwoStage;
    }
}
