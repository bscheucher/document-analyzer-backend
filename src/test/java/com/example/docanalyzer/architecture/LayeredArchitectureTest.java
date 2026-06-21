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
 * <p>Phase 1 note: the target packages ({@code web}, {@code domain},
 * {@code persistence}, {@code integration}) are still being populated, so the
 * rules are configured with optional/empty-allowed layers and currently pass
 * vacuously. As classes are moved into these packages in later phases, the
 * rules begin to bite automatically — no edits to this test are required. The
 * legacy packages ({@code controller}, {@code service}, {@code repository},
 * {@code entity}, {@code dto}) are intentionally left unconstrained until the
 * migration relocates them.
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

                // Nothing may depend on the inbound HTTP adapter.
                .whereLayer("Web").mayNotBeAccessedByAnyLayer()
                // The core may be used by every adapter, but the adapters
                // (persistence/integration) must not reach into each other.
                .whereLayer("Persistence").mayOnlyBeAccessedByLayers("Web", "Domain")
                .whereLayer("Integration").mayOnlyBeAccessedByLayers("Web", "Domain")

                // Empty layers are fine while the migration is in progress.
                .withOptionalLayers(true)
                .allowEmptyShould(true);

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
                        + "PDFBox and Reactor so it is unit-testable in isolation")
                // Domain is empty in phase 1; allow the rule to pass vacuously.
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }
}
