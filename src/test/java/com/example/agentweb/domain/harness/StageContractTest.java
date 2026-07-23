package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Stage Contract 值对象测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class StageContractTest {

    @Test
    void mvp_contracts_should_match_frozen_four_stage_order_and_outputs() {
        List<StageContract> contracts = StageContract.mvpDefaults();

        assertEquals(Arrays.asList(HarnessStage.values()), Arrays.asList(
                contracts.get(0).getStage(), contracts.get(1).getStage(),
                contracts.get(2).getStage(), contracts.get(3).getStage()));
        assertEquals(new HashSet<ArtifactType>(Arrays.asList(
                        ArtifactType.REQUIREMENT,
                        ArtifactType.ACCEPTANCE_CRITERIA,
                        ArtifactType.IMPACT_ANALYSIS,
                        ArtifactType.OPEN_QUESTIONS)),
                contracts.get(0).getRequiredOutputArtifacts());
        assertThrows(UnsupportedOperationException.class,
                () -> contracts.add(contracts.get(0)));
        assertThrows(UnsupportedOperationException.class,
                () -> contracts.get(0).getRequiredOutputArtifacts().add(ArtifactType.FINAL_REPORT));
    }

    @Test
    void contract_should_reject_empty_gate_or_output_contract() {
        assertThrows(IllegalArgumentException.class, () -> new StageContract(
                HarnessStage.ANALYSIS,
                Collections.<ArtifactType>emptySet(),
                Collections.<ArtifactType>emptySet(),
                Collections.singleton("gate"),
                "REQUIREMENT_BASELINE"));
        assertThrows(IllegalArgumentException.class, () -> new StageContract(
                HarnessStage.ANALYSIS,
                Collections.<ArtifactType>emptySet(),
                Collections.singleton(ArtifactType.REQUIREMENT),
                Collections.singleton(" "),
                "REQUIREMENT_BASELINE"));
    }
}
