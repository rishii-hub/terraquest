package dev.terraquest.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Module boundaries as build failures.
 *
 * <p>These rules are the difference between an architecture and an aspiration.
 * The provider abstraction only holds if nothing outside an adapter package can
 * name a concrete provider -- and "can" here means "compiles", not "should".
 * Every rule below encodes a decision already made and documented; if a rule
 * needs to change, that is an architecture discussion, not a test fix.
 */
@AnalyzeClasses(
        packages = "dev.terraquest",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // ---------------------------------------------------------------
    // Provider adapters are sealed behind their SPI
    // ---------------------------------------------------------------

    /**
     * The rule this whole PR exists to make enforceable. The original harvester
     * imported MapillaryClient directly; nothing can do that again without
     * breaking the build.
     */
    @ArchTest
    static final ArchRule provider_adapters_are_only_reachable_through_the_spi =
            noClasses()
                    .that().resideOutsideOfPackage("dev.terraquest.imagery.mapillary..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("dev.terraquest.imagery.mapillary..")
                    .because("Mapillary is one adapter behind ImageryProvider; the engine "
                            + "must compile unchanged if it is replaced or joined by others");

    /**
     * The AWS SDK is one {@code StorageProvider} adapter, no more privileged than
     * Mapillary is behind {@code ImageryProvider}. Sealing it here is what lets
     * the asset pipeline compile unchanged if storage ever moves off an
     * S3-compatible backend -- and keeps the local-filesystem and in-memory
     * providers honest by making an accidental S3 import a build failure.
     */
    @ArchTest
    static final ArchRule aws_sdk_is_sealed_behind_the_r2_adapter =
            noClasses()
                    .that().resideOutsideOfPackage("dev.terraquest.storage.r2..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("software.amazon.awssdk..")
                    .because("R2 is one StorageProvider adapter; the asset pipeline must "
                            + "compile unchanged if storage is replaced or joined by others");

    // ---------------------------------------------------------------
    // Game engine purity
    // ---------------------------------------------------------------

    /**
     * The game module owns rules and scoring. It may see the SPIs, never the
     * machinery behind them. If game logic needs something from ingestion or
     * pool management, that something belongs in an interface.
     */
    @ArchTest
    static final ArchRule game_module_depends_only_on_abstractions =
            noClasses()
                    .that().resideInAPackage("dev.terraquest.game..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "dev.terraquest.imagery.mapillary..",
                            "dev.terraquest.location.harvest..")
                    .because("game logic must not know where imagery comes from "
                            + "or how the pool is built");

    /**
     * Scoring is pure math. A scoring change must never require a database, a
     * network, or Spring to test -- that property is what makes the exponential
     * decay curve tunable with confidence.
     */
    @ArchTest
    static final ArchRule scoring_is_dependency_free =
            classes()
                    .that().resideInAPackage("dev.terraquest.scoring..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage(
                            "dev.terraquest.scoring..",
                            "dev.terraquest.shared..",
                            "java..",
                            "org.springframework.stereotype..")
                    .because("scoring is pure computation and must stay trivially testable");

    // ---------------------------------------------------------------
    // Shared kernel stays a kernel
    // ---------------------------------------------------------------

    /**
     * {@code shared} is for value types (GeoPoint and friends). The moment it
     * imports a feature module it becomes a dumping ground with dependency
     * cycles, which is how every "common" package dies.
     */
    @ArchTest
    static final ArchRule shared_kernel_depends_on_nothing_internal =
            noClasses()
                    .that().resideInAPackage("dev.terraquest.shared..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "dev.terraquest.game..",
                            "dev.terraquest.location..",
                            "dev.terraquest.imagery..",
                            "dev.terraquest.progression..",
                            "dev.terraquest.identity..")
                    .because("shared is a leaf; feature modules depend on it, never the reverse");

    // ---------------------------------------------------------------
    // Identity boundary
    // ---------------------------------------------------------------

    /**
     * Domain code sees a UserId and nothing else. Spring Security types leaking
     * into game or progression logic couples business rules to the auth stack
     * and is exactly the parallel-abstraction trap we chose to avoid by NOT
     * writing our own AuthenticationProvider.
     */
    @ArchTest
    static final ArchRule security_types_stay_at_the_edge =
            noClasses()
                    .that().resideInAnyPackage(
                            "dev.terraquest.game..",
                            "dev.terraquest.location..",
                            "dev.terraquest.progression..",
                            "dev.terraquest.scoring..")
                    .and().haveSimpleNameNotEndingWith("Controller")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.springframework.security..")
                    .because("domain code authenticates nobody; it receives a UserId from the edge");

    // ---------------------------------------------------------------
    // Sanity
    // ---------------------------------------------------------------

    @ArchTest
    static final ArchRule no_cycles_between_feature_modules =
            com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices()
                    .matching("dev.terraquest.(*)..")
                    .should().beFreeOfCycles();

    /** Guard against the test suite silently analysing an empty classpath. */
    static {
        JavaClasses imported = new ClassFileImporter().importPackages("dev.terraquest");
        if (imported.isEmpty()) {
            throw new IllegalStateException("ArchUnit imported zero classes; check package roots");
        }
    }
}
