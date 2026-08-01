package com.example.agentweb.infra.workbench.query;

import com.example.agentweb.app.workbench.operation.HighImpactOperationProjection;
import com.example.agentweb.app.workbench.operation.HighImpactOperationQueryService;
import com.example.agentweb.domain.workbench.HighImpactOperation;
import com.example.agentweb.domain.workbench.HighImpactOperationRepository;
import com.example.agentweb.domain.workbench.WorkbenchId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 高影响操作列表的 SQLite CQRS 投影查询。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqliteHighImpactOperationQueryService
        implements HighImpactOperationQueryService {

    private static final int MAXIMUM_RESULTS = 200;

    private final JdbcTemplate jdbc;
    private final HighImpactOperationRepository operationRepository;

    public SqliteHighImpactOperationQueryService(
            JdbcTemplate jdbc,
            HighImpactOperationRepository operationRepository) {
        this.jdbc = jdbc;
        this.operationRepository = operationRepository;
    }

    @Override
    public List<HighImpactOperationProjection> findByWorkbenchId(
            WorkbenchId workbenchId) {
        if (workbenchId == null) {
            throw new IllegalArgumentException(
                    "operation query workbench id must not be null");
        }
        List<String> operationIds = jdbc.query(
                "SELECT operation_id FROM workbench_high_impact_operation "
                        + "WHERE workbench_id=? "
                        + "ORDER BY proposed_at DESC, operation_id DESC LIMIT ?",
                (resultSet, rowNumber) -> resultSet.getString("operation_id"),
                workbenchId.getValue(), MAXIMUM_RESULTS);
        List<HighImpactOperationProjection> projections =
                new ArrayList<HighImpactOperationProjection>(operationIds.size());
        for (String operationId : operationIds) {
            HighImpactOperation operation = operationRepository.findById(operationId)
                    .orElseThrow(() -> new IllegalStateException(
                            "high-impact operation projection source is unavailable"));
            operation.requireWorkbench(workbenchId);
            projections.add(HighImpactOperationProjection.from(operation));
        }
        return Collections.unmodifiableList(projections);
    }
}
