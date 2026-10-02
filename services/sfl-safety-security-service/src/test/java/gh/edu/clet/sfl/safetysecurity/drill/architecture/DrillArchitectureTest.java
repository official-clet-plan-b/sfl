package gh.edu.clet.sfl.safetysecurity.drill.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * S175's layering, following {@code RiskAssessmentArchitectureTest}'s shape, plus the module's boundaries: exactly
 * one class may know S174 exists and exactly one may know S162a exists, and neither knows S175.
 */
@AnalyzeClasses(packages = "gh.edu.clet.sfl.safetysecurity", importOptions = ImportOption.DoNotIncludeTests.class)
class DrillArchitectureTest {

    @ArchTest
    static final ArchRule DOMAIN_IS_FRAMEWORK_FREE = noClasses().that().resideInAPackage("..drill.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..",
                    "jakarta.servlet..", "org.springframework.jdbc..", "tools.jackson..", "io.swagger..");

    @ArchTest
    static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_ADAPTERS = noClasses().that().resideInAPackage("..drill.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..drill.api..", "..drill.infrastructure..", "..drill.application..");

    @ArchTest
    static final ArchRule NOTHING_POINTS_INTO_INFRASTRUCTURE = noClasses().that()
            .resideInAnyPackage("..drill.api..", "..drill.application..", "..drill.domain..")
            .should().dependOnClassesThat().resideInAPackage("..drill.infrastructure..");

    /** SRS-SFL-S175-01 reaches S174 through its published {@code EmergencyDrillTrigger} and one adapter. */
    @ArchTest
    static final ArchRule ONLY_THE_ADAPTER_KNOWS_S174 = noClasses().that().resideInAPackage("..safetysecurity.drill..")
            .and().doNotHaveSimpleName("EmergencyDrillNotificationAdapter")
            .should().dependOnClassesThat().resideInAPackage("..safetysecurity.emergency..");

    /** SRS-SFL-S175-02 reaches S162a through its published {@code DrillMusterControl} and one adapter. */
    @ArchTest
    static final ArchRule ONLY_THE_ADAPTER_KNOWS_S162A = noClasses().that().resideInAPackage("..safetysecurity.drill..")
            .and().doNotHaveSimpleName("LifeSafetyDrillMusterAdapter")
            .should().dependOnClassesThat().resideInAPackage("..safetysecurity.lifesafety..");

    /**
     * And neither knows S175 - each publishes a contract and stops there. The SSEMP permissions route, which unions
     * every module's matrix, is the one exception.
     */
    @ArchTest
    static final ArchRule S174_AND_S162A_DO_NOT_KNOW_S175 = noClasses().that()
            .resideInAnyPackage("..safetysecurity.emergency..", "..safetysecurity.lifesafety..")
            .and().doNotHaveSimpleName("ActorPermissionsController")
            .should().dependOnClassesThat().resideInAPackage("..safetysecurity.drill..");
}
