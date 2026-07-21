package com.example.agentweb;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * DDD 分层护栏 A1—A5（detailed-design §0.2）。A4 用 FreezingArchRule 冻结存量
 * app→infra 违例（WorkflowRunner→AgentCliInvoker 等 33 处），增量零容忍。
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
                    "com.example.agentweb.interfaces..",
                    "com.example.agentweb.adapter..");

    @ArchTest
    static final ArchRule A2_PORTS_CONSUMED_ONLY_BY_APP_AND_INFRA = classes()
            .that().resideInAPackage("com.example.agentweb.adapter..")
            .should().onlyBeAccessed().byAnyPackage(
                    "com.example.agentweb.adapter..",
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
    static final ArchRule A4_APP_NOT_DEPEND_ON_INFRA_FROZEN = FreezingArchRule.freeze(noClasses()
            .that().resideInAPackage("com.example.agentweb.app..")
            .should().dependOnClassesThat().resideInAPackage("com.example.agentweb.infra.."));

    @ArchTest
    static final ArchRule A5_NEW_DOMAINS_PERSISTENCE_IGNORANT = noClasses()
            .that().resideInAnyPackage(
                    "com.example.agentweb.domain.requirement..",
                    "com.example.agentweb.domain.verification..",
                    "com.example.agentweb.domain.workspace..",
                    "com.example.agentweb.domain.delivery..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "org.hibernate..",
                    "com.fasterxml.jackson..");
}
