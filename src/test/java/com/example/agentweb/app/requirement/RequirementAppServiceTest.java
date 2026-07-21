package com.example.agentweb.app.requirement;

import com.example.agentweb.app.knowledge.KnowledgeHarvestService;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementNotFoundException;
import com.example.agentweb.domain.requirement.RequirementQuotaExceededException;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.requirement.RequirementSource;
import com.example.agentweb.domain.requirement.RequirementStatus;
import com.example.agentweb.domain.verification.VerificationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * App 编排测试：Mock Repository/QueryService + 真实聚合，只验编排顺序与透传，业务规则在 domain 测。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@ExtendWith(MockitoExtension.class)
public class RequirementAppServiceTest {

    private static final String OWNER = "V33215020";

    @Mock
    private RequirementRepository repository;

    @Mock
    private RequirementQueryService queryService;

    @Mock
    private KnowledgeHarvestService harvest;

    private RequirementAppService appService;

    @BeforeEach
    public void setUp() {
        RequirementProperties properties = new RequirementProperties();
        properties.getQuota().setMaxActivePerUser(2);
        appService = new RequirementAppService(repository, queryService, properties, harvest);
    }

    @Test
    public void create_should_check_quota_before_save_and_return_id() {
        when(queryService.countActiveByOwner(OWNER)).thenReturn(1);

        String id = appService.create("标题", "描述", OWNER, RequirementSource.BOARD);

        assertNotNull(id);
        InOrder order = inOrder(queryService, repository);
        order.verify(queryService).countActiveByOwner(OWNER);
        ArgumentCaptor<Requirement> captor = ArgumentCaptor.forClass(Requirement.class);
        order.verify(repository).save(captor.capture());
        assertEquals(RequirementStatus.INTAKE, captor.getValue().getStatus());
        assertEquals(OWNER, captor.getValue().getOwner());
    }

    @Test
    public void create_over_quota_should_reject_without_save() {
        when(queryService.countActiveByOwner(OWNER)).thenReturn(2);

        assertThrows(RequirementQuotaExceededException.class,
                () -> appService.create("标题", "描述", OWNER, RequirementSource.BOARD));
        verify(repository, never()).save(any());
    }

    @Test
    public void create_should_retry_with_new_id_on_primary_key_collision() {
        when(queryService.countActiveByOwner(OWNER)).thenReturn(0);
        doThrow(new DuplicateKeyException("PRIMARY KEY constraint failed: requirement.id"))
                .doNothing()
                .when(repository).save(any());

        String id = appService.create("标题", "描述", OWNER, RequirementSource.BOARD);

        ArgumentCaptor<Requirement> captor = ArgumentCaptor.forClass(Requirement.class);
        verify(repository, times(2)).save(captor.capture());
        assertNotEquals(captor.getAllValues().get(0).getId().getValue(),
                captor.getAllValues().get(1).getId().getValue());
        assertEquals(captor.getAllValues().get(1).getId().getValue(), id);
    }

    @Test
    public void create_should_retry_when_sqlite_dialect_reports_untranslated_conflict() {
        when(queryService.countActiveByOwner(OWNER)).thenReturn(0);
        doThrow(new DataIntegrityViolationException(
                "[SQLITE_CONSTRAINT_PRIMARYKEY] A PRIMARY KEY constraint failed "
                        + "(UNIQUE constraint failed: requirement.id)"))
                .doNothing()
                .when(repository).save(any());

        assertNotNull(appService.create("标题", "描述", OWNER, RequirementSource.BOARD));
        verify(repository, times(2)).save(any());
    }

    @Test
    public void create_should_give_up_after_exhausting_collision_retries() {
        when(queryService.countActiveByOwner(OWNER)).thenReturn(0);
        doThrow(new DuplicateKeyException("collision")).when(repository).save(any());

        assertThrows(DuplicateKeyException.class,
                () -> appService.create("标题", "描述", OWNER, RequirementSource.BOARD));
        verify(repository, times(3)).save(any());
    }

    @Test
    public void create_should_not_retry_on_non_conflict_failure() {
        when(queryService.countActiveByOwner(OWNER)).thenReturn(0);
        doThrow(new DataAccessResourceFailureException("db down")).when(repository).save(any());

        assertThrows(DataAccessResourceFailureException.class,
                () -> appService.create("标题", "描述", OWNER, RequirementSource.BOARD));
        verify(repository, times(1)).save(any());
    }

    @Test
    public void attachPlan_should_load_apply_then_update() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", "d", OWNER);
        when(repository.findById("R1")).thenReturn(requirement);

        appService.attachPlan("R1", "计划正文", OWNER);

        assertEquals(RequirementStatus.PLANNED, requirement.getStatus());
        assertEquals("计划正文", requirement.getPlan().getPlanText());
        verify(repository).update(requirement);
    }

    @Test
    public void approve_should_pass_actor_through_to_aggregate() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", "d", OWNER);
        requirement.attachPlan(planOf("p"), OWNER);
        when(repository.findById("R1")).thenReturn(requirement);

        appService.approve("R1", OWNER);

        assertEquals(RequirementStatus.APPROVED, requirement.getStatus());
        verify(repository).update(requirement);
    }

    @Test
    public void suspend_and_resume_should_orchestrate_load_then_update() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", "d", OWNER);
        when(repository.findById("R1")).thenReturn(requirement);

        appService.suspend("R1", OWNER, "等依赖");
        assertEquals(RequirementStatus.SUSPENDED, requirement.getStatus());

        appService.resume("R1", OWNER);
        assertEquals(RequirementStatus.INTAKE, requirement.getStatus());
    }

    @Test
    public void startFixRun_should_load_apply_then_update() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", "d", OWNER);
        requirement.attachPlan(planOf("p"), OWNER);
        requirement.approve(OWNER);
        requirement.attachWorkspace("W1");
        requirement.startImplement(OWNER);
        when(repository.findById("R1")).thenReturn(requirement);

        appService.startFixRun("R1", OWNER);

        assertEquals(RequirementStatus.IMPLEMENTING, requirement.getStatus());
        verify(repository).update(requirement);
    }

    @Test
    public void unknown_requirement_should_throw_not_found() {
        when(repository.findById("R-missing")).thenReturn(null);

        assertThrows(RequirementNotFoundException.class, () -> appService.approve("R-missing", OWNER));
        verify(repository, never()).update(any());
    }

    @Test
    public void markDelivered_should_update_then_harvest_knowledge() {
        Requirement requirement = reviewRequirement();
        when(repository.findById("R1")).thenReturn(requirement);

        appService.markDelivered("R1", OWNER, "MR !7 merged");

        assertEquals(RequirementStatus.DELIVERED, requirement.getStatus());
        InOrder order = inOrder(repository, harvest);
        order.verify(repository).update(requirement);
        order.verify(harvest).harvestOnDelivered("R1", "MR !7 merged");
    }

    @Test
    public void markDelivered_without_harvest_channel_should_not_throw() {
        RequirementAppService noHarvest = new RequirementAppService(
                repository, queryService, new RequirementProperties(), null);
        Requirement requirement = reviewRequirement();
        when(repository.findById("R1")).thenReturn(requirement);

        assertDoesNotThrow(() -> noHarvest.markDelivered("R1", OWNER, "MR !7"));
        verify(repository).update(requirement);
    }

    private Requirement reviewRequirement() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", "d", OWNER);
        requirement.attachPlan(planOf("p"), OWNER);
        requirement.approve(OWNER);
        requirement.attachWorkspace("W1");
        requirement.startImplement(OWNER);
        requirement.startVerify(OWNER);
        requirement.applyVerificationOutcome(VerificationOutcome.VERIFIED, OWNER);
        return requirement;
    }

    private com.example.agentweb.domain.requirement.AgentPlan planOf(String text) {
        return new com.example.agentweb.domain.requirement.AgentPlan(text, null, null, java.time.Instant.now());
    }
}
