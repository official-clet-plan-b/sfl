package gh.edu.clet.sfl.safetysecurity.permit.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * S164's layering, following {@code DrillArchitectureTest}'s shape, plus the module's boundaries: exactly one class may know each sibling it
 * depends on (S165, S160a, S163, S174), and none of them knows S164.
 */
@AnalyzeClasses(packages = "gh.edu.clet.sfl.safetysecurity", importOptions = ImportOption.DoNotIncludeTests.class)
class PermitArchitectureTest {

    @ArchTest
    static final ArchRule DOMAIN_IS_FRAMEWORK_FREE = noClasses().that().resideInAPackage("..permit.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..",
                    "jakarta.servlet..", "org.springframework.jdbc..", "tools.jackson..", "io.swagger..");

    @ArchTest
    static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_ADAPTERS = noClasses().that().resideInAPackage("..permit.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..permit.api..", "..permit.infrastructure..", "..permit.application..");

    @ArchTest
    static final ArchRule NOTHING_POINTS_INTO_INFRASTRUCTURE = noClasses().that()
            .resideInAnyPackage("..permit.api..", "..permit.application..", "..permit.domain..")
            .should().dependOnClassesThat().resideInAPackage("..permit.infrastructure..");

    /** SRS-SFL-S164-01 reaches S165 through its published {@code RiskAssessmentDirectory} and one adapter. */
    @ArchTest
    static final ArchRule ONLY_ONE_ADAPTER_KNOWS_S165 = noClasses().that().resideInAPackage("..safetysecurity.permit..")
            .and().doNotHaveSimpleName("RiskAssessmentDirectoryAdapter")
            .should().dependOnClassesThat().resideInAPackage("..safetysecurity.riskassessment..");

    /** The zone a permit names is checked through S160a's published {@code AccessZoneDirectory} and one adapter. */
    @ArchTest
    static final ArchRule ONLY_ONE_ADAPTER_KNOWS_S160A = noClasses().that().resideInAPackage("..safetysecurity.permit..")
            .and().doNotHaveSimpleName("AccessZoneAdapter")
            .should().dependOnClassesThat().resideInAPackage("..safetysecurity.accesscontrol..");

    /** SRS-SFL-S164-05 is S163 -> S164 through S163's published {@code IncidentRiskObserver} and one adapter. */
    @ArchTest
    static final ArchRule ONLY_ONE_ADAPTER_KNOWS_S163 = noClasses().that().resideInAPackage("..safetysecurity.permit..")
            .and().doNotHaveSimpleName("IncidentPermitFlagAdapter")
            .should().dependOnClassesThat().resideInAPackage("..safetysecurity.incident..");

    /** SRS-SFL-S164-03 is S174 -> S164 through S174's published {@code EmergencyActivationObserver} and one adapter. */
    @ArchTest
    static final ArchRule ONLY_ONE_ADAPTER_KNOWS_S174 = noClasses().that().resideInAPackage("..safetysecurity.permit..")
            .and().doNotHaveSimpleName("EmergencyPermitFlagAdapter")
            .should().dependOnClassesThat().resideInAPackage("..safetysecurity.emergency..");

    /** And none of them knows S164 - each publishes a contract and stops there. The permissions route, which unions every module's matrix, is the one exception. */
    @ArchTest
    static final ArchRule SIBLINGS_DO_NOT_KNOW_S164 = noClasses().that()
            .resideInAnyPackage("..safetysecurity.riskassessment..", "..safetysecurity.accesscontrol..", "..safetysecurity.incident..", "..safetysecurity.emergency..")
            .and().doNotHaveSimpleName("ActorPermissionsController")
            .should().dependOnClassesThat().resideInAPackage("..safetysecurity.permit..");
}
