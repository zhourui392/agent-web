/**
 * Workbench Run owner-scoped transport client contract.
 *
 * @author alex
 * @since 2026-08-01
 */
import { describe, expect, it, vi } from "vitest";
import {
  WorkbenchRunApiError,
  createWorkbenchRunApiClient,
  type WorkbenchRunFetch,
} from "../../frontend/js/api/workbench-run.js";

type FetchMock = ReturnType<typeof vi.fn>;

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: vi.fn().mockResolvedValue(JSON.stringify(body)),
  } as unknown as Response;
}

function clientWith(response: Response): {
  client: ReturnType<typeof createWorkbenchRunApiClient>;
  fetchMock: FetchMock;
} {
  const fetchMock = vi.fn().mockResolvedValue(response);
  return {
    client: createWorkbenchRunApiClient(fetchMock as WorkbenchRunFetch),
    fetchMock,
  };
}

function acceptedSubmission(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    runId: "run-1",
    sessionId: "session-1",
    status: "PENDING",
    phaseStatus: "IN_PROGRESS",
    workbenchVersion: 8,
    capabilitySnapshotHash: "sha256:capability",
    repositoryScopeHash: "sha256:scope",
    replayed: false,
    ...overrides,
  };
}

describe("Workbench Run API client", () => {
  it("ensures a Phase conversation with exact optimistic version and safe projection", async () => {
    const response = {
      sessionId: "phase-session-1",
      generation: 0,
      workbenchVersion: 8,
      created: true,
      repositoryRoot: "/private/repository",
    };
    const { client, fetchMock } = clientWith(jsonResponse(200, response));

    await expect(client.ensureConversation(
      "wb/一", "REQUIREMENT_ANALYSIS", 7,
    )).resolves.toEqual({
      sessionId: "phase-session-1",
      generation: 0,
      workbenchVersion: 8,
      created: true,
    });
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/workbenches/wb%2F%E4%B8%80/phases/REQUIREMENT_ANALYSIS/conversation",
      {
        method: "POST",
        headers: { "If-Match": "7" },
      },
    );
  });

  it("submits an owner-scoped Review run with concurrency headers and all optional input", async () => {
    const accepted = acceptedSubmission();
    const { client, fetchMock } = clientWith(jsonResponse(202, accepted));

    await expect(
      client.submitRun({
        workbenchId: "wb/一 二?",
        phase: "REVIEW_REFACTOR",
        expectedVersion: 7,
        idempotencyKey: "run-key-1",
        request: {
          message: "实现并测试",
          runMode: "MODIFY_WORKSPACE",
          handoffSourceVersion: 3,
          reviewConfirmationId: "review/1",
          attachments: [
            { attachmentId: "attachment/1", kind: "FILE" },
            { attachmentId: "attachment-2", kind: "IMAGE" },
          ],
        },
      }),
    ).resolves.toEqual(accepted);

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C%3F/phases/REVIEW_REFACTOR/runs",
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "If-Match": "7",
          "Idempotency-Key": "run-key-1",
        },
        body: JSON.stringify({
          message: "实现并测试",
          runMode: "MODIFY_WORKSPACE",
          handoffSourceVersion: 3,
          reviewConfirmationId: "review/1",
          attachments: [
            { attachmentId: "attachment/1", kind: "FILE" },
            { attachmentId: "attachment-2", kind: "IMAGE" },
          ],
        }),
      },
    );
  });

  it("omits optional submit properties instead of inventing contract defaults", async () => {
    const { client, fetchMock } = clientWith(
      jsonResponse(202, acceptedSubmission()),
    );

    await client.submitRun({
      workbenchId: "wb-1",
      phase: "SOLUTION_DESIGN",
      expectedVersion: 1,
      idempotencyKey: "run-key-2",
      request: {
        message: "讨论方案",
        runMode: "DISCUSS_READ_ONLY",
      },
    });

    expect(
      JSON.parse(String((fetchMock.mock.calls[0][1] as RequestInit).body)),
    ).toEqual({
      message: "讨论方案",
      runMode: "DISCUSS_READ_ONLY",
    });
  });

  it("requires an explicit confirmation only for a Review MODIFY run", async () => {
    const { client, fetchMock } = clientWith(
      jsonResponse(202, acceptedSubmission()),
    );
    const base = {
      workbenchId: "wb-1",
      expectedVersion: 7,
      idempotencyKey: "run-key-review",
    };

    await expect(
      client.submitRun({
        ...base,
        phase: "REVIEW_REFACTOR",
        request: {
          message: "请改一下",
          runMode: "MODIFY_WORKSPACE",
        },
      }),
    ).rejects.toThrow("review confirmation");
    await expect(
      client.submitRun({
        ...base,
        phase: "REVIEW_REFACTOR",
        request: {
          message: "执行已确认的重构",
          runMode: "MODIFY_WORKSPACE",
          reviewConfirmationId: "   ",
        },
      }),
    ).rejects.toThrow("review confirmation");
    expect(fetchMock).not.toHaveBeenCalled();

    await client.submitRun({
      ...base,
      phase: "REVIEW_REFACTOR",
      request: {
        message: "执行已确认的重构并运行受影响测试",
        runMode: "MODIFY_WORKSPACE",
        reviewConfirmationId: "confirmation-7",
      },
    });
    expect(
      JSON.parse(String((fetchMock.mock.calls[0][1] as RequestInit).body)),
    ).toEqual({
      message: "执行已确认的重构并运行受影响测试",
      runMode: "MODIFY_WORKSPACE",
      reviewConfirmationId: "confirmation-7",
    });
  });

  it("rejects a Review confirmation attached to read-only or non-Review runs", async () => {
    const { client, fetchMock } = clientWith(
      jsonResponse(202, acceptedSubmission()),
    );
    const base = {
      workbenchId: "wb-1",
      expectedVersion: 7,
      idempotencyKey: "run-key-review",
      request: {
        message: "讨论意见",
        runMode: "DISCUSS_READ_ONLY" as const,
        reviewConfirmationId: "confirmation-7",
      },
    };

    await expect(
      client.submitRun({ ...base, phase: "REVIEW_REFACTOR" }),
    ).rejects.toThrow("only valid");
    await expect(
      client.submitRun({
        ...base,
        phase: "IMPLEMENT_TEST",
        request: { ...base.request, runMode: "MODIFY_WORKSPACE" },
      }),
    ).rejects.toThrow("only valid");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("requires explicit If-Match and Idempotency-Key values before submit", async () => {
    const { client, fetchMock } = clientWith(jsonResponse(202, {}));
    const base = {
      workbenchId: "wb-1",
      phase: "IMPLEMENT_TEST" as const,
      request: { message: "implement", runMode: "MODIFY_WORKSPACE" as const },
    };

    await expect(
      client.submitRun({
        ...base,
        expectedVersion: Number.NaN,
        idempotencyKey: "key-1",
      }),
    ).rejects.toThrow("If-Match");
    await expect(
      client.submitRun({
        ...base,
        expectedVersion: 1,
        idempotencyKey: "   ",
      }),
    ).rejects.toThrow("Idempotency-Key");
    await expect(
      client.submitRun({
        ...base,
        expectedVersion: 1,
        idempotencyKey: "x".repeat(129),
      }),
    ).rejects.toThrow("Idempotency-Key");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("projects submit, detail, and stop success bodies onto explicit safe fields", async () => {
    const leak = {
      workingDir: "/home/private/project",
      token: "secret-value",
      nestedSecret: { apiKey: "secret-value" },
    };
    const detail = {
      runId: "run-1",
      workbenchId: "wb-1",
      phase: "IMPLEMENT_TEST",
      sessionId: "session-1",
      status: "RUNNING",
      runMode: "MODIFY_WORKSPACE",
      lastEventSeq: 9,
    };
    const stop = { runId: "run-1", status: "CANCEL_REQUESTED" };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse(202, { ...acceptedSubmission(), ...leak }),
      )
      .mockResolvedValueOnce(jsonResponse(200, { ...detail, ...leak }))
      .mockResolvedValueOnce(jsonResponse(202, { ...stop, ...leak }));
    const client = createWorkbenchRunApiClient(fetchMock as WorkbenchRunFetch);

    const submitted = await client.submitRun({
      workbenchId: "wb-1",
      phase: "IMPLEMENT_TEST",
      expectedVersion: 7,
      idempotencyKey: "run-key-1",
      request: { message: "implement", runMode: "MODIFY_WORKSPACE" },
    });
    const loaded = await client.getRun("wb-1", "run-1");
    const stopped = await client.stopRun("wb-1", "run-1");

    expect(submitted).toEqual(acceptedSubmission());
    expect(loaded).toEqual(detail);
    expect(stopped).toEqual(stop);
    expect(JSON.stringify({ submitted, loaded, stopped })).not.toMatch(
      /home|workingDir|secret|token|apiKey/i,
    );
  });

  it.each([
    [
      "submit",
      202,
      acceptedSubmission({
        sessionId: undefined,
        workingDir: "/home/private",
        token: "secret",
      }),
    ],
    [
      "submit",
      202,
      acceptedSubmission({
        workbenchVersion: -1,
        workingDir: "/home/private",
        token: "secret",
      }),
    ],
    [
      "detail",
      200,
      {
        runId: "run-1",
        workbenchId: "wb-1",
        phase: "INVALID_PHASE",
        sessionId: "session-1",
        status: "RUNNING",
        runMode: "INVALID_MODE",
        lastEventSeq: -1,
        workingDir: "/home/private",
        token: "secret",
      },
    ],
    [
      "stop",
      202,
      {
        runId: "x".repeat(129),
        status: "UNKNOWN",
        workingDir: "/home/private",
        token: "secret",
      },
    ],
  ] as const)(
    "rejects an invalid %s success projection",
    async (operation, status, body) => {
      const { client } = clientWith(jsonResponse(status, body));

      const result =
        operation === "submit"
          ? client.submitRun({
              workbenchId: "wb-1",
              phase: "IMPLEMENT_TEST",
              expectedVersion: 7,
              idempotencyKey: "run-key-1",
              request: { message: "implement", runMode: "MODIFY_WORKSPACE" },
            })
          : operation === "detail"
            ? client.getRun("wb-1", "run-1")
            : client.stopRun("wb-1", "run-1");
      const rejection = await result.catch((error) => error);

      expect(rejection).toEqual(
        expect.objectContaining({
          status,
          code: "WORKBENCH_RUN_UNEXPECTED_RESPONSE",
        }),
      );
      expect(JSON.stringify(rejection)).not.toMatch(/home|secret|token/i);
    },
  );

  it("gets run detail and requests stop through encoded owner-scoped URLs", async () => {
    const detail = {
      runId: "run/一",
      workbenchId: "wb/一 二?",
      phase: "IMPLEMENT_TEST",
      sessionId: "session-1",
      status: "RUNNING",
      runMode: "MODIFY_WORKSPACE",
      lastEventSeq: 9,
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, detail))
      .mockResolvedValueOnce(
        jsonResponse(202, {
          runId: "run/一",
          status: "CANCEL_REQUESTED",
        }),
      );
    const client = createWorkbenchRunApiClient(fetchMock as WorkbenchRunFetch);

    await expect(client.getRun("wb/一 二?", "run/一")).resolves.toEqual(detail);
    await expect(client.stopRun("wb/一 二?", "run/一")).resolves.toEqual({
      runId: "run/一",
      status: "CANCEL_REQUESTED",
    });

    const base =
      "/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C%3F/runs/run%2F%E4%B8%80";
    expect(fetchMock.mock.calls[0]).toEqual([base, { method: "GET" }]);
    expect(fetchMock.mock.calls[1]).toEqual([
      `${base}/stop`,
      { method: "POST" },
    ]);
  });

  it("builds an encoded events URL without opening an SSE connection", () => {
    const fetchMock = vi.fn();
    const client = createWorkbenchRunApiClient(fetchMock as WorkbenchRunFetch);

    expect(client.eventsUrl("wb/一 二?", "run/一")).toBe(
      "/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C%3F/runs/run%2F%E4%B8%80/events",
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects dot path segments before fetch while retaining encoded slash and Unicode support", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse(202, acceptedSubmission()));
    const client = createWorkbenchRunApiClient(fetchMock as WorkbenchRunFetch);
    const command = {
      workbenchId: "wb-1",
      phase: "IMPLEMENT_TEST" as const,
      expectedVersion: 7,
      idempotencyKey: "run-key-1",
      request: { message: "implement", runMode: "MODIFY_WORKSPACE" as const },
    };

    await expect(
      client.submitRun({ ...command, workbenchId: "." }),
    ).rejects.toThrow("workbenchId");
    await expect(
      client.submitRun({ ...command, phase: ".." as "IMPLEMENT_TEST" }),
    ).rejects.toThrow("phase");
    await expect(client.getRun("..", "run-1")).rejects.toThrow("workbenchId");
    await expect(client.getRun("wb-1", ".")).rejects.toThrow("runId");
    await expect(client.stopRun(".", "run-1")).rejects.toThrow("workbenchId");
    await expect(client.stopRun("wb-1", "..")).rejects.toThrow("runId");
    expect(() => client.eventsUrl(".", "run-1")).toThrow("workbenchId");
    expect(() => client.eventsUrl("wb-1", "..")).toThrow("runId");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each([
    [401, "AUTHENTICATION_REQUIRED"],
    [403, "ACCESS_DENIED"],
    [404, "WORKBENCH_RUN_NOT_FOUND"],
    [409, "WORKBENCH_RUN_CONFLICT"],
    [410, "WORKBENCH_RUN_CURSOR_EXPIRED"],
    [422, "WORKBENCH_RUN_INVALID"],
    [503, "WORKBENCH_RUN_UNAVAILABLE"],
  ])(
    "exposes only safe error fields for HTTP %i",
    async (status, fallbackCode) => {
      const { client } = clientWith(
        jsonResponse(status, {
          code:
            status === 409
              ? "WORKBENCH_VERSION_CONFLICT"
              : status === 503
                ? "SECRET_VALUE"
                : "../unsafe-code",
          message: "failed in /home/private/project using secret-value",
          workingDir: "/home/private/project",
          token: "secret-value",
        }),
      );

      const rejection = await client
        .getRun("wb-1", "run-1")
        .catch((error) => error);

      expect(rejection).toBeInstanceOf(WorkbenchRunApiError);
      expect(rejection).toEqual(
        expect.objectContaining({
          status,
          code: status === 409 ? "WORKBENCH_VERSION_CONFLICT" : fallbackCode,
          message: expect.any(String),
        }),
      );
      expect(Object.keys(rejection).sort()).toEqual(["code", "name", "status"]);
      expect(JSON.stringify(rejection)).not.toMatch(
        /home|workingDir|secret|token/i,
      );
      expect(rejection.message).not.toMatch(/home|workingDir|secret|token/i);
    },
  );

  it("sanitizes network failures and unexpected success statuses", async () => {
    const networkFetch = vi
      .fn()
      .mockRejectedValue(
        new Error("connect failed for /home/private/project with secret-value"),
      );
    const networkClient = createWorkbenchRunApiClient(
      networkFetch as WorkbenchRunFetch,
    );
    const networkError = await networkClient
      .getRun("wb-1", "run-1")
      .catch((error) => error);

    expect(networkError).toEqual(
      expect.objectContaining({
        status: 0,
        code: "WORKBENCH_RUN_NETWORK_ERROR",
      }),
    );
    expect(networkError.message).not.toMatch(/home|secret/i);

    const { client } = clientWith(
      jsonResponse(201, {
        message:
          "unexpected response at /home/private/project with secret-value",
      }),
    );
    const statusError = await client
      .getRun("wb-1", "run-1")
      .catch((error) => error);
    expect(statusError).toEqual(
      expect.objectContaining({
        status: 201,
        code: "WORKBENCH_RUN_UNEXPECTED_RESPONSE",
      }),
    );
    expect(statusError.message).not.toMatch(/home|secret/i);
  });
});
