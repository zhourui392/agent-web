package com.example.agentweb;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * DDD 分层护栏。Domain 零外层依赖，Application 零 Infrastructure 具体依赖。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@AnalyzeClasses(packages = "com.example.agentweb", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    @ArchTest
    static final ArchRule A1_DOMAIN_ZERO_OUTWARD_DEPENDENCY = noClasses()
            .that().resideInAPackage("com.example.agentweb.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.example.agentweb.app..",
                    "com.example.agentweb.infra..",
                    "com.example.agentweb.interfaces..");

    @ArchTest
    static final ArchRule A2_APP_PORTS_CONSUMED_ONLY_BY_APP_AND_INFRA = classes()
            .that().resideInAPackage("com.example.agentweb.app..port..")
            .should().onlyBeAccessed().byAnyPackage(
                    "com.example.agentweb.app..",
                    "com.example.agentweb.infra..",
                    "com.example.agentweb.config..");

    @ArchTest
    static final ArchRule A3_PROVIDER_PACKAGE_BOUNDARY = noClasses()
            .that().resideInAnyPackage(
                    "com.example.agentweb.app..",
                    "com.example.agentweb.domain..",
                    "com.example.agentweb.interfaces..")
            .should().dependOnClassesThat().resideInAPackage("com.example.agentweb.infra.provider..");

    @ArchTest
    static final ArchRule A4_APP_NOT_DEPEND_ON_INFRA = noClasses()
            .that().resideInAPackage("com.example.agentweb.app..")
            .should().dependOnClassesThat().resideInAPackage("com.example.agentweb.infra..");

    @ArchTest
    static final ArchRule A5_HARNESS_DOMAIN_SEPARATE_FROM_WORKFLOW = noClasses()
            .that().resideInAPackage("com.example.agentweb.domain.harness..")
            .should().dependOnClassesThat().resideInAPackage("com.example.agentweb.domain.workflow..");

    @ArchTest
    static final ArchRule A6_WORKFLOW_DOMAIN_SEPARATE_FROM_HARNESS = noClasses()
            .that().resideInAPackage("com.example.agentweb.domain.workflow..")
            .should().dependOnClassesThat().resideInAPackage("com.example.agentweb.domain.harness..");
}
