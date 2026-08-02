package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.operation.HighImpactOperationTargetInput;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 固定 API 支持的四种高影响操作 Target DTO。
 *
 * @author alex
 * @since 2026-08-01
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(
                value = GitCommitOperationTargetRequest.class,
                name = "GIT_COMMIT"),
        @JsonSubTypes.Type(
                value = GitPushOperationTargetRequest.class,
                name = "GIT_PUSH"),
        @JsonSubTypes.Type(
                value = LocalDeployOperationTargetRequest.class,
                name = "LOCAL_DEPLOY"),
        @JsonSubTypes.Type(
                value = ProductionWriteOperationTargetRequest.class,
                name = "PRODUCTION_WRITE")
})
public interface HighImpactOperationTargetRequest {

    HighImpactOperationTargetInput toApplicationTarget();
}
