package gh.edu.clet.sfl.safetysecurity.riskassessment.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * S165's layering, following {@code LifeSafetyArchitectureTest}'s shape, plus the module's own boundary:
 * exactly one class may know the incident module exists.
 */
@AnalyzeClasses(packages = {"gh.edu.clet.sfl.safetysecurity.riskassessment", "gh.edu.clet.sfl.safetysecurity.incident"},
        importOptions = ImportOption.DoNotIncludeTests.class)
class RiskAssessmentArchitectureTest {

    @ArchTest
    static final ArchRule DOMAIN_IS_FRAMEWORK_FREE = noClasses().that().resideInAPackage("..riskassessment.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..",
                    "jakarta.servlet..", "org.springframework.jdbc..", "tools.jackson..", "io.swagger..");

    @ArchTest
    static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_ADAPTERS = noClasses().that()
            .resideInAPackage("..riskassessment.domain..").should().dependOnClassesThat()
            .resideInAnyPackage("..riskassessment.api..", "..riskassessment.infrastructure..",
                    "..riskassessment.application..");

    @ArchTest
    static final ArchRule NOTHING_POINTS_INTO_INFRASTRUCTURE = noClasses().that()
            .resideInAnyPackage("..riskassessment.api..", "..riskassessment.application..",
                    "..riskassessment.domain..")
            .should().dependOnClassesThat().resideInAPackage("..riskassessment.infrastructure..");

    /**
     * SRS-SFL-S165-04 is S163 -> S165, through S163's published {@code IncidentRiskObserver} contract and one
     * adapter. Any other S165 class importing the incident module would couple the two the way the playbook's
     * "one adapter class per sibling" rule exists to prevent.
     */
    @ArchTest
    static final ArchRule ONLY_THE_ADAPTER_KNOWS_THE_INCIDENT_MODULE = noClasses().that()
            .resideInAPackage("..riskassessment..")
            .and().doNotHaveSimpleName("IncidentReviewFlagAdapter")
            .should().dependOnClassesThat().resideInAPackage("..safetysecurity.incident..");

    /** And the incident module knows nothing of S165 - it publishes a contract and stops there. */
    @ArchTest
    static final ArchRule INCIDENT_DOES_NOT_KNOW_S165 = noClasses().that().resideInAPackage("..safetysecurity.incident..")
            .should().dependOnClassesThat().resideInAPackage("..safetysecurity.riskassessment..");
}
