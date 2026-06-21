package com.example.docanalyzer.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Executable contract for the ports &amp; adapters layout described in
 * {@code src/docs/layered-architecture-refactor.md}.
 *
 * <p>Dependencies point inward: {@code web → domain ← persistence} and
 * {@code web → domain ← integration}. The domain depends on nobody and on no
 * framework. The {@code config} package is the composition root and is
 * intentionally left out of the layer set (it may wire any layer); only
 * dependencies between the four defined layers are considered.
 */
class LayeredArchitectureTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.docanalyzer");
    }

    @Test
    void layersRespectTheDependencyRule() {
        ArchRule rule = layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Web").definedBy("..web..")
                .layer("Domain").definedBy("..domain..")
                .layer("Persistence").definedBy("..persistence..")
                .layer("Integration").definedBy("..integration..")

                // The domain core is the only layer anything may depend on, and
                // only the adapters (and the web entry point) may depend on it.
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Web", "Persistence", "Integration")
                // The inbound web adapter and the outbound adapters are all
                // "leaf" layers: nothing in the hexagon depends on them (they are
                // reached only by the config composition root via dependency
                // injection, which is not part of the layer set).
                .whereLayer("Web").mayNotBeAccessedByAnyLayer()
                .whereLayer("Persistence").mayNotBeAccessedByAnyLayer()
                .whereLayer("Integration").mayNotBeAccessedByAnyLayer();

        rule.check(productionClasses);
    }

    @Test
    void domainIsFrameworkFree() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "jakarta.servlet..",
                        "org.apache.pdfbox..",
                        "reactor.."
                )
                .because("the domain core must stay free of Spring, JPA, Servlet, "
                        + "PDFBox and Reactor so it is unit-testable in isolation");

        rule.check(productionClasses);
    }
}
