# agent-web Prometheus monitoring

This directory contains Prometheus alert rules for metrics exported from the loopback-only Spring
Boot management socket (`127.0.0.1:18093/actuator/prometheus` by default):

- `native-diagnosis-alerts.yml`: NATIVE diagnosis readiness and execution alerts.
- `workbench-alerts.yml`: Workbench run, SSE, capability, recovery and repository-scope alerts.

Production Prometheus must load both files, or translate the same expressions to the selected
monitoring platform. A typical Prometheus configuration contains:

```yaml
rule_files:
  - /etc/prometheus/rules/native-diagnosis-alerts.yml
  - /etc/prometheus/rules/workbench-alerts.yml
```

Keep the management port inaccessible from the public reverse proxy. Route `critical` and
`warning` alerts to an owned receiver. A deployment is not production-ready until the collector
target is `UP`, Prometheus has loaded both rule groups, and an explicitly arranged test alert has
reached the receiver.

## Workbench metric export contract

The Workbench Micrometer adapter uses dotted meter names. Prometheus normalizes dots to
underscores and adds type suffixes:

| Micrometer meter | Prometheus series used by rules | Labels |
| --- | --- | --- |
| `workbench.run` counter | `workbench_run_total` | `phase`, `mode`, `status` |
| `workbench.write.conflict` counter | `workbench_write_conflict_total` | none |
| `workbench.sse.reconnect` counter | `workbench_sse_reconnect_total` | `result` |
| `workbench.event.lag` summary, base unit seconds | `workbench_event_lag_seconds_sum`, `workbench_event_lag_seconds_count` | none |
| `workbench.capability.resolution` counter | `workbench_capability_resolution_total` | `result` |
| `workbench.recovery.reconciliation` counter | `workbench_recovery_reconciliation_total` | `result` |
| `workbench.workspace.scope.violation` counter | `workbench_workspace_scope_violation_total` | none |
| `workbench.creation` counter | `workbench_creation_total` | `result` |
| `workbench.active` gauge | `workbench_active` | none |

`phase`, `mode` and terminal `status` are uppercase enum names. A cancelled run is an expected
Owner action and is not counted as a run failure; the run-failure alert counts only `FAILED` and
`INTERRUPTED`. SSE and capability result `SUCCESS` is the only success value used by their ratio
rules, so `UNKNOWN` and any stable non-success code are fail-closed failures.

Recovery instrumentation must use an uppercase bounded result. Successful results such as
`TERMINAL_RECONCILED` must not contain `FAILED`, `ERROR` or `UNKNOWN`; any recovery result
containing one of those tokens is treated as an immediate reconciliation failure. Changing these
result semantics requires changing and validating the alert expression in the same release.

Micrometer counters are registered lazily. An absent counter series can mean that no matching event
has occurred; it does not prove success. Verify the scrape target separately. `workbench_active`
counts active Workbench aggregates, not active Runtime processes or write leases, and must not be
used as the precondition for disabling the Workbench total switch.

## Workbench alert thresholds

| Alert | Threshold and `for` | Severity | Trigger semantics |
| --- | --- | --- | --- |
| `WorkbenchRunFailureRatioHigh` | failure ratio over 20% for 10m, minimum 5 terminal runs in 10m | warning | `FAILED` and `INTERRUPTED` terminal runs indicate sustained Runtime or reconciliation degradation |
| `WorkbenchWriteConflictBurst` | at least 3 conflicts in 10m, condition present for 5m | warning | repeated optimistic/write-lease contention; a single expected concurrent rejection does not alert |
| `WorkbenchSseReconnectFailureRatioHigh` | non-`SUCCESS` ratio over 20% for 10m, minimum 5 attempts | warning | reconnect or cursor recovery is persistently failing rather than experiencing one transient disconnect |
| `WorkbenchEventLagHigh` | mean lag over 5s for 10m, minimum 5 samples per rolling 5m | warning | persisted events are reaching Workbench consumers too slowly; this is a mean, not a p95 |
| `WorkbenchCapabilityResolutionFailureRatioHigh` | non-`SUCCESS` ratio over 20% for 10m, minimum 5 resolutions | warning | Phase Profile/Rule/Skill/MCP resolution is persistently unavailable or invalid |
| `WorkbenchRecoveryReconciliationFailed` | any failed/error/unknown recovery result in 10m, no delay | critical | an existing run cannot be reconciled safely; write runs must not be replayed automatically |
| `WorkbenchWorkspaceScopeViolation` | any violation in 5m, no delay | critical | a Runtime/file operation crossed or could not be mapped to the selected repository scope |

Thresholds are initial release safeguards, not completed capacity baselines. Revisit them using
trial traffic and recorded false-positive/false-negative evidence; do not relax them merely to make
an alert quiet.

## Feature-flag rollout and rollback handling

Before enabling any Workbench release flag:

1. Verify the management target is `UP`, both rule groups are loaded, and warning/critical routing
   has an owner.
2. Arrange a non-production test alert and confirm receiver delivery. Do not manufacture a
   workspace scope violation to test routing.
3. Confirm there are no unresolved recovery or scope alerts. Confirm active Runtime/write-lease
   state through the application/database operational query, not `workbench_active`.
4. Open `agent.workbench.enabled`, create, write-run and high-impact flags only in the reviewed
   rollout order. High-impact flags remain independent and default closed.

When closing `create-enabled` or `write-run-enabled`, keep these rules loaded. Existing runs must
remain stoppable and recoverable, and recovery/scope alerts remain actionable until all active runs
reach an explicit terminal or reconciled state. A lack of new counter samples after a flag is closed
is expected and is not evidence that rollback succeeded.

For a planned application rollback, use only a time-bounded, ticket-linked maintenance silence for
alerts whose traffic is intentionally interrupted. Do not silence
`WorkbenchRecoveryReconciliationFailed` or `WorkbenchWorkspaceScopeViolation`. Keep Workbench
creation/write/high-impact flags closed while reconciling unknown runs, preserve SQLite/runtime
evidence, and never replay a write run automatically. After the previous version is healthy, remove
the silence, verify the target and rule groups again, and confirm no unresolved recovery/scope alert
before reopening flags.

These are minimum operating instructions. This repository does not claim that alert delivery,
feature-flag closure or rollback has been rehearsed; production readiness requires a dated exercise
record with participants, observed alerts, decisions and recovery evidence.
