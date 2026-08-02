package com.example.agentweb;

import com.example.agentweb.app.runtime.architecturefixture.ProviderSdkLeak;
import com.example.agentweb.interfaces.architecturefixture.ProviderCliLeak;
import com.example.agentweb.interfaces.workbench.architecturefixture.WorkbenchInfraLeak;
import com.example.agentweb.interfaces.workbench.architecturefixture.WorkbenchRepositoryLeak;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DDD 分层护栏。Domain 零外层依赖，Application 零 Infrastructure 具体依赖。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@AnalyzeClasses(packages = "com.example.agentweb", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    private static final Set<String> WORKBENCH_INTERFACE_DOMAIN_TYPES =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    "com.example.agentweb.domain.auth.CurrentUserProvider",
                    "com.example.agentweb.domain.auth.LoginUser",
                    "com.example.agentweb.domain.auth.UserContext",
                    "com.example.agentweb.domain.chatrun.ChatRunStatus",
                    "com.example.agentweb.app.runtime.port.RuntimePreflightException",
                    "com.example.agentweb.domain.capability.CapabilityCatalogException",
                    "com.example.agentweb.domain.capability.CapabilityResolutionException",
                    "com.example.agentweb.domain.shared.AgentType",
                    "com.example.agentweb.domain.workbench.WorkbenchDomainException",
                    "com.example.agentweb.domain.workbench.WorkbenchErrorCode",
                    "com.example.agentweb.domain.workbench.DocumentReference",
                    "com.example.agentweb.domain.workbench.HighImpactOperationDecision",
                    "com.example.agentweb.domain.workbench.WorkbenchId",
                    "com.example.agentweb.domain.workbench.OwnerReference",
                    "com.example.agentweb.domain.workbench.WorkbenchRunAttachmentReference",
                    "com.example.agentweb.domain.workbench.WorkbenchRunAttachmentType",
                    "com.example.agentweb.domain.workbench.WorkbenchAdministrator",
                    "com.example.agentweb.domain.workbench.WorkbenchPhase",
                    "com.example.agentweb.domain.workbench.WorkbenchPhaseStatus",
                    "com.example.agentweb.domain.workbench.WorkbenchStatus")));

    private static final DescribedPredicate<JavaClass>
            WORKBENCH_INTERFACE_ALLOWED_DEPENDENCY =
            resideOutsideOfPackage("com.example.agentweb..")
                    .or(resideInAnyPackage(
                            "com.example.agentweb.interfaces.workbench..",
                            "com.example.agentweb.app.workbench.."))
                    .or(DescribedPredicate.describe(
                            "be an explicitly approved Workbench boundary type",
                            javaClass -> WORKBENCH_INTERFACE_DOMAIN_TYPES.contains(
                                    javaClass.getName())));

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
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.example.agentweb.config.nativeagent..",
                    "com.example.agentweb.infra.agentrun..",
                    "com.example.agentweb.infra.cli..",
                    "com.example.agentweb.infra.nativeagent..");

    @ArchTest
    static final ArchRule A4_APP_NOT_DEPEND_ON_INFRA = noClasses()
            .that().resideInAPackage("com.example.agentweb.app..")
            .should().dependOnClassesThat().resideInAPackage("com.example.agentweb.infra..");

    @ArchTest
    static final ArchRule A7_AGENTKIT_NOT_EXPOSED_TO_CORE_OR_INTERFACES = noClasses()
            .that().resideInAnyPackage(
                    "com.example.agentweb.app..",
                    "com.example.agentweb.domain..",
                    "com.example.agentweb.interfaces..")
            .should().dependOnClassesThat().resideInAPackage("com.anthropic.agentkit..");

    @ArchTest
    static final ArchRule A8_AGENTKIT_DEPENDENCIES_CONFINED_TO_NATIVE_ADAPTER = noClasses()
            .that().resideOutsideOfPackages(
                    "com.example.agentweb.config.nativeagent..",
                    "com.example.agentweb.infra.nativeagent..")
            .should().dependOnClassesThat().resideInAPackage("com.anthropic.agentkit..");

    @ArchTest
    static final ArchRule A15_WORKBENCH_INTERFACE_NOT_DEPEND_ON_REPOSITORY = noClasses()
            .that().resideInAPackage("com.example.agentweb.interfaces.workbench..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

    @ArchTest
    static final ArchRule A16_WORKBENCH_INTERFACE_NOT_DEPEND_ON_INFRA = noClasses()
            .that().resideInAPackage("com.example.agentweb.interfaces.workbench..")
            .should().dependOnClassesThat().resideInAPackage(
                    "com.example.agentweb.infra..");

    @ArchTest
    static final ArchRule A17_WORKBENCH_INTERFACE_DEPENDENCY_WHITELIST = classes()
            .that().resideInAPackage("com.example.agentweb.interfaces.workbench..")
            .should().onlyDependOnClassesThat(WORKBENCH_INTERFACE_ALLOWED_DEPENDENCY);

    @ArchTest
    static final ArchRule A18_WORKBENCH_RUN_ATTACHMENT_UNION_IS_DOMAIN = classes()
            .that().haveSimpleName("WorkbenchRunAttachmentReference")
            .or().haveSimpleName("WorkbenchRunAttachmentType")
            .should().resideInAPackage(
                    "com.example.agentweb.domain.workbench");

    @Test
    void workbenchInterfaceRulesShouldRejectRepositoryAndInfrastructureLeaks() {
        assertRejects(A15_WORKBENCH_INTERFACE_NOT_DEPEND_ON_REPOSITORY,
                WorkbenchRepositoryLeak.class);
        assertRejects(A16_WORKBENCH_INTERFACE_NOT_DEPEND_ON_INFRA,
                WorkbenchInfraLeak.class);
        assertRejects(A17_WORKBENCH_INTERFACE_DEPENDENCY_WHITELIST,
                WorkbenchRepositoryLeak.class);
        assertRejects(A17_WORKBENCH_INTERFACE_DEPENDENCY_WHITELIST,
                WorkbenchInfraLeak.class);
    }

    @Test
    void providerRulesShouldRejectCliAdapterAndSdkLeaks() {
        assertRejects(A3_PROVIDER_PACKAGE_BOUNDARY, ProviderCliLeak.class);
        assertRejects(A7_AGENTKIT_NOT_EXPOSED_TO_CORE_OR_INTERFACES,
                ProviderSdkLeak.class);
    }

    @Test
    void springProxiedClassesShouldNotBeFinal() {
        JavaClasses mainClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.example.agentweb");
        List<String> violations = new ArrayList<String>();
        for (JavaClass javaClass : mainClasses) {
            boolean transactional = javaClass.isAnnotatedWith(
                    Transactional.class)
                    || javaClass.getMethods().stream().anyMatch(method ->
                    method.isAnnotatedWith(Transactional.class));
            boolean repository = javaClass.isAnnotatedWith(Repository.class);
            if ((transactional || repository)
                    && javaClass.getModifiers().contains(JavaModifier.FINAL)) {
                violations.add(javaClass.getName());
            }
        }
        assertFalse(violations.size() > 0,
                () -> "Spring proxied classes must not be final: " + violations);
    }

    private static void assertRejects(ArchRule rule, Class<?> violatingClass) {
        JavaClasses fixture = new ClassFileImporter().importClasses(violatingClass);
        EvaluationResult result = rule.evaluate(fixture);
        assertTrue(result.hasViolation(),
                () -> rule.getDescription() + " should reject " + violatingClass.getName());
        assertTrue(result.getFailureReport().toString().contains(violatingClass.getName()),
                () -> result.getFailureReport().toString());
    }
}
