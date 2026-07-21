package com.example.agentweb.app.knowledge;

import com.example.agentweb.domain.knowledge.KnowledgeScope;
import com.example.agentweb.domain.knowledge.KnowledgeSuggestion;
import com.example.agentweb.domain.knowledge.KnowledgeSuggestionRepository;
import com.example.agentweb.domain.knowledge.SuggestionStatus;
import com.example.agentweb.domain.requirement.AgentPlan;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.requirement.RequirementSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 关单收割编排：DELIVERED 后产 PENDING 候选进收件箱；幂等、旁路降级、计划截断。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class KnowledgeHarvestServiceTest {

    private static final String REQ_ID = "R2607040001";
    private static final String SOURCE = "MR !12 merged by zhourui";

    private KnowledgeSuggestionRepository suggestionRepository;
    private RequirementRepository requirementRepository;
    private KnowledgeHarvestService service;

    @BeforeEach
    public void setUp() {
        suggestionRepository = mock(KnowledgeSuggestionRepository.class);
        requirementRepository = mock(RequirementRepository.class);
        service = new KnowledgeHarvestService(suggestionRepository, requirementRepository);
    }

    @Test
    public void harvest_should_create_pending_candidate_from_requirement() {
        when(suggestionRepository.existsForRequirement(REQ_ID)).thenReturn(false);
        when(requirementRepository.findById(REQ_ID)).thenReturn(requirementWithPlan("修复计划正文"));

        service.harvestOnDelivered(REQ_ID, SOURCE);

        ArgumentCaptor<KnowledgeSuggestion> captor = ArgumentCaptor.forClass(KnowledgeSuggestion.class);
        verify(suggestionRepository).save(captor.capture());
        KnowledgeSuggestion saved = captor.getValue();
        assertEquals(SuggestionStatus.PENDING, saved.getStatus());
        assertEquals(REQ_ID, saved.getRequirementId());
        assertEquals(KnowledgeScope.REPO, saved.getScope());
        assertEquals(SOURCE, saved.getSourceRef());
        assertTrue(saved.getTitle().contains("下单超时修复"));
        assertTrue(saved.getRootCause().contains("修复计划正文"));
        assertTrue(saved.getSolution().contains(SOURCE));
    }

    @Test
    public void harvest_should_skip_when_candidate_already_exists() {
        when(suggestionRepository.existsForRequirement(REQ_ID)).thenReturn(true);

        service.harvestOnDelivered(REQ_ID, SOURCE);

        verify(suggestionRepository, never()).save(any());
        verify(requirementRepository, never()).findById(any());
    }

    @Test
    public void harvest_should_skip_when_requirement_missing() {
        when(suggestionRepository.existsForRequirement(REQ_ID)).thenReturn(false);
        when(requirementRepository.findById(REQ_ID)).thenReturn(null);

        service.harvestOnDelivered(REQ_ID, SOURCE);

        verify(suggestionRepository, never()).save(any());
    }

    @Test
    public void harvest_should_swallow_repository_failure() {
        when(suggestionRepository.existsForRequirement(REQ_ID)).thenReturn(false);
        when(requirementRepository.findById(REQ_ID)).thenReturn(requirementWithPlan("plan"));
        doThrow(new IllegalStateException("db down")).when(suggestionRepository).save(any());

        assertDoesNotThrow(() -> service.harvestOnDelivered(REQ_ID, SOURCE));
    }

    @Test
    public void harvest_should_truncate_long_plan_text() {
        when(suggestionRepository.existsForRequirement(REQ_ID)).thenReturn(false);
        when(requirementRepository.findById(REQ_ID)).thenReturn(requirementWithPlan("计".repeat(3000)));

        service.harvestOnDelivered(REQ_ID, SOURCE);

        ArgumentCaptor<KnowledgeSuggestion> captor = ArgumentCaptor.forClass(KnowledgeSuggestion.class);
        verify(suggestionRepository).save(captor.capture());
        assertEquals(2000, captor.getValue().getRootCause().length());
    }

    private Requirement requirementWithPlan(String planText) {
        Requirement requirement = Requirement.create(RequirementSource.BOARD,
                "下单超时修复", "下单接口偶发超时", "V33215020");
        requirement.attachPlan(new AgentPlan(planText, null, null, Instant.now()), "V33215020");
        return requirement;
    }
}
