package com.example.agentweb.app.requirement;

import com.example.agentweb.domain.requirement.OwnerUnresolvedException;
import com.example.agentweb.domain.requirement.RequirementSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部建需求：幂等命中、owner 回落链、幂等键缺省不去重。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class ExternalIntakeServiceTest {

    private RequirementAppService appService;
    private RequirementIdempotencyStore idempotencyStore;
    private RequirementProperties properties;
    private com.example.agentweb.adapter.UserDirectory userDirectory;
    private ExternalIntakeService service;

    @BeforeEach
    public void setUp() {
        appService = mock(RequirementAppService.class);
        idempotencyStore = mock(RequirementIdempotencyStore.class);
        properties = new RequirementProperties();
        userDirectory = mock(com.example.agentweb.adapter.UserDirectory.class);
        when(userDirectory.containsUser(anyString())).thenReturn(true);
        service = new ExternalIntakeService(appService, idempotencyStore, properties, userDirectory);
    }

    @Test
    public void intake_should_return_existing_on_idempotency_hit() {
        when(idempotencyStore.findRequirementId("key-a", "idem-1")).thenReturn(Optional.of("REXIST"));

        ExternalIntakeService.IntakeOutcome outcome = service.intake("key-a", "idem-1", request("V1"));

        assertEquals("REXIST", outcome.getRequirementId());
        assertTrue(outcome.isDuplicated());
        verify(appService, never()).createWithRef(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    public void intake_should_create_and_record_with_rest_api_source() {
        when(idempotencyStore.findRequirementId("key-a", "idem-1")).thenReturn(Optional.empty());
        when(appService.createWithRef(eq(RequirementSource.REST_API), eq("http://doc/1"),
                eq("标题"), eq("描述"), eq("V1"))).thenReturn("RNEW");

        ExternalIntakeService.IntakeOutcome outcome = service.intake("key-a", "idem-1", request("V1"));

        assertEquals("RNEW", outcome.getRequirementId());
        assertFalse(outcome.isDuplicated());
        verify(idempotencyStore).record(eq("key-a"), eq("idem-1"), eq("RNEW"), any());
    }

    @Test
    public void intake_without_idempotency_key_should_skip_dedup() {
        when(appService.createWithRef(any(), any(), anyString(), anyString(), anyString()))
                .thenReturn("RNEW");

        service.intake("key-a", null, request("V1"));

        verify(idempotencyStore, never()).findRequirementId(anyString(), anyString());
        verify(idempotencyStore, never()).record(anyString(), anyString(), anyString(), any());
    }

    @Test
    public void intake_should_fallback_owner_then_reject_when_unresolvable() {
        properties.getIntake().setDefaultOwner("V999");
        when(appService.createWithRef(any(), any(), anyString(), anyString(), eq("V999")))
                .thenReturn("RNEW");

        service.intake("key-a", null, request(" "));
        verify(appService).createWithRef(any(), any(), anyString(), anyString(), eq("V999"));

        properties.getIntake().setDefaultOwner("");
        assertThrows(OwnerUnresolvedException.class,
                () -> service.intake("key-a", null, request(null)));
    }

    @Test
    public void intake_should_skip_owner_missing_from_directory_and_use_fallback() {
        properties.getIntake().setDefaultOwner("V999");
        when(userDirectory.containsUser("outsider")).thenReturn(false);
        when(appService.createWithRef(any(), any(), anyString(), anyString(), eq("V999")))
                .thenReturn("RNEW");

        service.intake("key-a", null, request("outsider"));

        verify(appService).createWithRef(any(), any(), anyString(), anyString(), eq("V999"));
    }

    @Test
    public void intake_should_reject_when_owner_and_fallback_both_missing_from_directory() {
        properties.getIntake().setDefaultOwner("V999");
        when(userDirectory.containsUser(anyString())).thenReturn(false);

        assertThrows(OwnerUnresolvedException.class,
                () -> service.intake("key-a", null, request("outsider")));
        verify(appService, never()).createWithRef(any(), any(), anyString(), anyString(), anyString());
    }

    private ExternalIntakeService.ExternalRequirementRequest request(String owner) {
        return new ExternalIntakeService.ExternalRequirementRequest("标题", "描述", "http://doc/1", owner);
    }
}
