/**
 * TD-07 Phase Handoff 传输契约与安全投影。
 *
 * @author alex
 * @since 2026-08-01
 */
import { describe, expect, it, vi } from "vitest";
import {
  createWorkbenchHandoffApiClient,
  type PhaseHandoffView,
  type WorkbenchHandoffFetch,
} from "../../frontend/js/api/workbench-handoff.js";

const HASH_V1 = "a".repeat(64);
const HASH_V2 = "b".repeat(64);

function handoff(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    sourcePhase: "REQUIREMENT_ANALYSIS",
    summary: "已确认 MVP 范围",
    decisions: [{ text: "采用四阶段工作台", rationale: "保持上下文隔离" }],
    openQuestions: [{ text: "是否接入候选生成？", ownerHint: "产品" }],
    pinnedFiles: [
      { repositoryKey: "agent-web", relativePath: "docs/design.md" },
    ],
    referencedRuns: [
      {
        runId: "run-1",
        phase: "REQUIREMENT_ANALYSIS",
        safeSummary: "需求分析完成",
      },
    ],
    version: 3,
    contentHash: HASH_V1,
    updatedAt: 1_722_528_000_000,
    readOnly: false,
    ...overrides,
  };
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(body == null ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("workbench handoff API", () => {
  it("accepts the initial handoff and reception projections at version zero", async () => {
    const initialFetch = vi
      .fn<WorkbenchHandoffFetch>()
      .mockResolvedValue(jsonResponse(200, handoff({ version: 0 })));

    await expect(
      createWorkbenchHandoffApiClient(initialFetch).getHandoff(
        "wb-1",
        "REQUIREMENT_ANALYSIS",
      ),
    ).resolves.toEqual(expect.objectContaining({ version: 0 }));

    const reception = {
      sourcePhase: "REQUIREMENT_ANALYSIS",
      sourceVersion: 0,
      sourceHash: HASH_V1,
      acceptedAt: 0,
    };
    const receptionFetch = vi
      .fn<WorkbenchHandoffFetch>()
      .mockResolvedValue(jsonResponse(200, reception));
    await expect(
      createWorkbenchHandoffApiClient(receptionFetch).acceptHandoffReception(
        "wb-1",
        "SOLUTION_DESIGN",
        {
          sourcePhase: "REQUIREMENT_ANALYSIS",
          sourceVersion: 0,
          sourceHash: HASH_V1,
        },
      ),
    ).resolves.toEqual(reception);
    expect(receptionFetch).toHaveBeenCalledWith(
      "/api/workbenches/wb-1/phases/SOLUTION_DESIGN/handoff-receptions",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          sourcePhase: "REQUIREMENT_ANALYSIS",
          sourceVersion: 0,
          sourceHash: HASH_V1,
        }),
      }),
    );
  });

  it("encodes path segments and sends PUT with the exact editable content and If-Match", async () => {
    const fetcher = vi
      .fn<WorkbenchHandoffFetch>()
      .mockResolvedValue(
        jsonResponse(200, handoff({ version: 4, contentHash: HASH_V2 })),
      );
    const client = createWorkbenchHandoffApiClient(fetcher);
    const content = {
      summary: "本地草稿",
      decisions: [{ text: "保留结构化交接", rationale: null }],
      openQuestions: [{ text: "如何验收？", ownerHint: null }],
      pinnedFiles: [
        { repositoryKey: "service/api", relativePath: "docs/设计.md" },
      ],
      referencedRuns: [
        {
          runId: "run/一",
          phase: "REQUIREMENT_ANALYSIS" as const,
          safeSummary: null,
        },
      ],
    };

    await client.putHandoff("wb/一 二?", "REQUIREMENT_ANALYSIS", 3, content);

    expect(fetcher).toHaveBeenCalledWith(
      "/api/workbenches/wb%2F%E4%B8%80%20%E4%BA%8C%3F/phases/REQUIREMENT_ANALYSIS/handoff",
      expect.objectContaining({
        method: "PUT",
        credentials: "same-origin",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          "If-Match": "3",
        },
        body: JSON.stringify({
          ...content,
          referencedRuns: [{ runId: "run/一" }],
        }),
      }),
    );
  });

  it("uses only Workbench-scoped GET/source/accept endpoints and a minimal reception body", async () => {
    const source = {
      targetPhase: "SOLUTION_DESIGN",
      latestSource: handoff(),
      reception: null,
      acceptedSource: null,
      stale: false,
      diff: null,
    };
    const reception = {
      sourcePhase: "REQUIREMENT_ANALYSIS",
      sourceVersion: 3,
      sourceHash: HASH_V1,
      acceptedAt: 1_722_528_000_001,
    };
    const fetcher = vi
      .fn<WorkbenchHandoffFetch>()
      .mockResolvedValueOnce(jsonResponse(200, handoff()))
      .mockResolvedValueOnce(jsonResponse(200, source))
      .mockResolvedValueOnce(jsonResponse(200, reception));
    const client = createWorkbenchHandoffApiClient(fetcher);

    await client.getHandoff("wb-1", "REQUIREMENT_ANALYSIS");
    await client.getHandoffSource("wb-1", "SOLUTION_DESIGN");
    await client.acceptHandoffReception("wb-1", "SOLUTION_DESIGN", {
      sourcePhase: "REQUIREMENT_ANALYSIS",
      sourceVersion: 3,
      sourceHash: HASH_V1,
    });

    expect(fetcher.mock.calls.map((call) => call[0])).toEqual([
      "/api/workbenches/wb-1/phases/REQUIREMENT_ANALYSIS/handoff",
      "/api/workbenches/wb-1/phases/SOLUTION_DESIGN/handoff-source",
      "/api/workbenches/wb-1/phases/SOLUTION_DESIGN/handoff-receptions",
    ]);
    expect(fetcher.mock.calls[0][1]).toEqual(
      expect.objectContaining({
        method: "GET",
        credentials: "same-origin",
      }),
    );
    expect(fetcher.mock.calls[1][1]).toEqual(
      expect.objectContaining({
        method: "GET",
        credentials: "same-origin",
      }),
    );
    expect(fetcher.mock.calls[2][1]).toEqual(
      expect.objectContaining({
        method: "POST",
        credentials: "same-origin",
        body: JSON.stringify({
          sourcePhase: "REQUIREMENT_ANALYSIS",
          sourceVersion: 3,
          sourceHash: HASH_V1,
        }),
      }),
    );
  });

  it("projects allowlisted DTO fields without leaking owner ids, absolute paths, or secrets", async () => {
    const fetcher = vi.fn<WorkbenchHandoffFetch>().mockResolvedValue(
      jsonResponse(
        200,
        handoff({
          updatedBy: { ownerId: "owner-secret", ownerName: "Alice" },
          absolutePath: "/home/alex/private",
          token: "secret-token",
          pinnedFiles: [
            {
              repositoryKey: "agent-web",
              relativePath: "docs/design.md",
              absolutePath: "/home/alex/private/docs/design.md",
            },
          ],
          referencedRuns: [
            {
              runId: "run-1",
              phase: "REQUIREMENT_ANALYSIS",
              safeSummary: "安全摘要",
              prompt: "含敏感上下文",
            },
          ],
        }),
      ),
    );
    const client = createWorkbenchHandoffApiClient(fetcher);

    const result = await client.getHandoff("wb-1", "REQUIREMENT_ANALYSIS");

    expect(result).toEqual<PhaseHandoffView>(
      expect.objectContaining({
        sourcePhase: "REQUIREMENT_ANALYSIS",
        pinnedFiles: [
          { repositoryKey: "agent-web", relativePath: "docs/design.md" },
        ],
        referencedRuns: [
          {
            runId: "run-1",
            phase: "REQUIREMENT_ANALYSIS",
            safeSummary: "安全摘要",
          },
        ],
      }),
    );
    expect(JSON.stringify(result)).not.toMatch(
      /ownerId|absolutePath|private|token|prompt|secret/i,
    );
  });

  it("returns null only for the explicit handoff-not-found contract", async () => {
    const missing = vi
      .fn<WorkbenchHandoffFetch>()
      .mockResolvedValue(
        jsonResponse(404, { code: "WORKBENCH_HANDOFF_NOT_FOUND" }),
      );
    const client = createWorkbenchHandoffApiClient(missing);

    await expect(
      client.getHandoff("wb-1", "REQUIREMENT_ANALYSIS"),
    ).resolves.toBeNull();

    const other = vi
      .fn<WorkbenchHandoffFetch>()
      .mockResolvedValue(jsonResponse(404, { code: "WORKBENCH_NOT_FOUND" }));
    await expect(
      createWorkbenchHandoffApiClient(other).getHandoff(
        "wb-1",
        "REQUIREMENT_ANALYSIS",
      ),
    ).rejects.toMatchObject({ status: 404, code: "WORKBENCH_NOT_FOUND" });
  });

  it("keeps the local-safe current projection on 409 and discards hostile error details", async () => {
    const current = handoff({ version: 4, contentHash: HASH_V2 });
    const fetcher = vi.fn<WorkbenchHandoffFetch>().mockResolvedValue(
      jsonResponse(409, {
        code: "WORKBENCH_HANDOFF_VERSION_CONFLICT",
        message: "SQL failed for /home/alex/private",
        stack: "secret stack",
        cause: { token: "secret-token" },
        current: {
          ...current,
          ownerId: "owner-secret",
          absolutePath: "/home/alex/private",
        },
      }),
    );
    const client = createWorkbenchHandoffApiClient(fetcher);

    const error = await client
      .putHandoff("wb-1", "REQUIREMENT_ANALYSIS", 3, {
        summary: "本地草稿",
        decisions: [],
        openQuestions: [],
        pinnedFiles: [],
        referencedRuns: [],
      })
      .catch((value) => value);

    expect(error).toMatchObject({
      status: 409,
      code: "WORKBENCH_HANDOFF_VERSION_CONFLICT",
      message: "Workbench handoff request failed",
      current: expect.objectContaining({ version: 4, contentHash: HASH_V2 }),
    });
    expect(JSON.stringify(error)).not.toMatch(
      /SQL|home|owner-secret|stack|cause|token/i,
    );
    expect(error.stack).toBeUndefined();
  });

  it("fails closed on malformed phases, hashes, versions, controls, and oversized content", async () => {
    const fetcher = vi
      .fn<WorkbenchHandoffFetch>()
      .mockResolvedValue(jsonResponse(200, handoff()));
    const client = createWorkbenchHandoffApiClient(fetcher);

    await expect(
      client.getHandoff("wb-1", "UNKNOWN" as never),
    ).rejects.toMatchObject({
      status: 400,
      code: "WORKBENCH_HANDOFF_REQUEST_INVALID",
    });
    await expect(
      client.acceptHandoffReception("wb-1", "SOLUTION_DESIGN", {
        sourcePhase: "REQUIREMENT_ANALYSIS",
        sourceVersion: 3,
        sourceHash: "not-a-hash",
      }),
    ).rejects.toMatchObject({
      status: 400,
      code: "WORKBENCH_HANDOFF_REQUEST_INVALID",
    });
    await expect(
      client.putHandoff("wb-1", "REQUIREMENT_ANALYSIS", -1, {
        summary: "invalid\u0000",
        decisions: [],
        openQuestions: [],
        pinnedFiles: [],
        referencedRuns: [],
      }),
    ).rejects.toMatchObject({
      status: 400,
      code: "WORKBENCH_HANDOFF_REQUEST_INVALID",
    });
    await expect(
      client.putHandoff("wb-1", "REQUIREMENT_ANALYSIS", 0, {
        summary: "x".repeat(8001),
        decisions: [],
        openQuestions: [],
        pinnedFiles: [],
        referencedRuns: [],
      }),
    ).rejects.toMatchObject({
      status: 400,
      code: "WORKBENCH_HANDOFF_REQUEST_INVALID",
    });
    await expect(
      client.putHandoff("wb-1", "REQUIREMENT_ANALYSIS", 0, {
        summary: "",
        decisions: Array.from({ length: 50 }, (_, index) => ({
          text: `${index}-${"测".repeat(1996)}`,
          rationale: null,
        })),
        openQuestions: [],
        pinnedFiles: [],
        referencedRuns: [],
      }),
    ).rejects.toMatchObject({
      status: 400,
      code: "WORKBENCH_HANDOFF_REQUEST_INVALID",
    });
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("rejects malformed or identity-mismatched response projections", async () => {
    const malformed = [
      handoff({ sourcePhase: "SOLUTION_DESIGN" }),
      handoff({ version: 1.5 }),
      handoff({ contentHash: "secret" }),
      handoff({ summary: "bad\u0000value" }),
      handoff({
        pinnedFiles: [
          { repositoryKey: "agent-web", relativePath: "/etc/passwd" },
        ],
      }),
    ];

    for (const body of malformed) {
      const client = createWorkbenchHandoffApiClient(
        vi
          .fn<WorkbenchHandoffFetch>()
          .mockResolvedValue(jsonResponse(200, body)),
      );
      await expect(
        client.getHandoff("wb-1", "REQUIREMENT_ANALYSIS"),
      ).rejects.toMatchObject({
        status: 200,
        code: "WORKBENCH_HANDOFF_RESPONSE_INVALID",
        message: "Workbench handoff request failed",
      });
    }
  });

  it("sanitizes network failures without retaining the thrown message or cause", async () => {
    const fetcher = vi
      .fn<WorkbenchHandoffFetch>()
      .mockRejectedValue(new Error("connect /home/alex/private token=secret"));
    const client = createWorkbenchHandoffApiClient(fetcher);

    const error = await client
      .getHandoff("wb-1", "REQUIREMENT_ANALYSIS")
      .catch((value) => value);

    expect(error).toMatchObject({
      status: 0,
      code: "WORKBENCH_HANDOFF_REQUEST_FAILED",
      message: "Workbench handoff request failed",
    });
    expect(JSON.stringify(error)).not.toMatch(/home|token|secret|cause/i);
  });
});
