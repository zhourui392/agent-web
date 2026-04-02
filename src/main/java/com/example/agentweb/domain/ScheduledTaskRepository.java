package com.example.agentweb.domain;

import java.time.Instant;
import java.util.List;

/**
 * Port for persisting scheduled tasks.
 */
public interface ScheduledTaskRepository {

    void save(ScheduledTask task);

    void update(ScheduledTask task);

    ScheduledTask findById(String id);

    List<ScheduledTask> findAll();

    List<ScheduledTask> findAllEnabled();

    void deleteById(String id);

    void updateLastRun(String id, Instant lastRunAt, String lastSessionId);
}
