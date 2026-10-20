package ca.twintigers.research.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** The hexagonal layering, checked on the compiled classes of every module. */
class LayeringTest {

    static JavaClasses classes;

    @BeforeAll
    static void load() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ca.twintigers.research");
    }

    @Test
    void layersOnlyDependInward() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Domain").definedBy("ca.twintigers.research.domain..")
                .layer("Ports").definedBy("ca.twintigers.research.ports..")
                .layer("Application").definedBy("ca.twintigers.research.application..")
                .layer("Adapters").definedBy("ca.twintigers.research.adapters..")
                .layer("Apps").definedBy("ca.twintigers.research.mcp..", "ca.twintigers.research.cli..", "ca.twintigers.research.worker..", "ca.twintigers.research.web..")
                .whereLayer("Apps").mayNotBeAccessedByAnyLayer()
                .whereLayer("Adapters").mayOnlyBeAccessedByLayers("Apps")
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters", "Apps")
                .whereLayer("Ports").mayOnlyBeAccessedByLayers("Application", "Adapters", "Apps")
                .check(classes);
    }

    @Test
    void domainIsPureJava() {
        classes().that().resideInAPackage("ca.twintigers.research.domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("ca.twintigers.research.domain..", "java..")
                .because("the domain holds the rules and calculations; it must not depend on frameworks, I/O or JSON")
                .check(classes);
    }

    @Test
    void portsAndApplicationUseNoInfrastructureLibraries() {
        noClasses().that().resideInAnyPackage("ca.twintigers.research.ports..", "ca.twintigers.research.application..")
                .should().dependOnClassesThat().resideInAnyPackage("tools.jackson..", "java.net.http..", "java.sql..",
                        "org.flywaydb..", "io.github.resilience4j..", "io.modelcontextprotocol..")
                .check(classes);
    }

    @Test
    void domainDoesNoIo() {
        noClasses().that().resideInAPackage("ca.twintigers.research.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("java.io..", "java.nio.file..", "java.net..")
                .orShould().callMethod(System.class, "currentTimeMillis")
                .check(classes);
    }

    @Test
    void ghostfolioIsReplaceable() {
        noClasses().that().resideOutsideOfPackage("ca.twintigers.research.adapters.ghostfolio..")
                .and().doNotHaveFullyQualifiedName("ca.twintigers.research.adapters.Wiring")
                .should().dependOnClassesThat().resideInAPackage("ca.twintigers.research.adapters.ghostfolio..")
                .because("the UI, MCP tools and monitor must use the PortfolioSource port only, so Ghostfolio can be "
                        + "swapped for another source by configuration (ADR 0008); only the composition root names it")
                .check(classes);
    }

    @Test
    void portfolioSourcesAreOnlyWiredInTheCompositionRoot() {
        noClasses().that().resideOutsideOfPackages("ca.twintigers.research.adapters.local..", "ca.twintigers.research.adapters.ghostfolio..")
                .and().doNotHaveFullyQualifiedName("ca.twintigers.research.adapters.Wiring")
                .should().dependOnClassesThat().resideInAnyPackage("ca.twintigers.research.adapters.local..")
                .check(classes);
    }

    @Test
    void noCycles() {
        slices().matching("ca.twintigers.research.(*)..").should().beFreeOfCycles().check(classes);
    }
}
