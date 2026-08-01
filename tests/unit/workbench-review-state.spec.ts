/**
 * Review Opinion 与 MODIFY Confirmation 的纯前端授权状态语义。
 *
 * @author alex
 * @since 2026-08-01
 */
import { describe, expect, it } from "vitest";
import {
  applyWorkbenchReviewConfirmation,
  applyWorkbenchReviewOpinion,
  beginWorkbenchReviewConfirmation,
  beginWorkbenchReviewOpinion,
  createWorkbenchReviewState,
  failWorkbenchReviewRequest,
  reviewModifyConfirmationId,
  switchWorkbenchReviewScope,
  type WorkbenchReviewScope,
} from "../../frontend/js/lib/workbench-review-state.js";

const HASH_V1 = "a".repeat(64);
const HASH_V2 = "b".repeat(64);

const REVIEW_SCOPE: WorkbenchReviewScope = {
  ownerId: "owner-1",
  workbenchId: "workbench-1",
  phase: "REVIEW_REFACTOR",
};

function confirmedState() {
  let state = createWorkbenchReviewState(REVIEW_SCOPE);
  const opinionRequest = beginWorkbenchReviewOpinion(state);
  state = applyWorkbenchReviewOpinion(
    opinionRequest.state,
    opinionRequest.token,
    {
      version: 7,
      contentHash: HASH_V1,
    },
  );
  const confirmationRequest = beginWorkbenchReviewConfirmation(state);
  expect(confirmationRequest).not.toBeNull();
  state = applyWorkbenchReviewConfirmation(
    confirmationRequest!.state,
    confirmationRequest!.token,
    {
      confirmationId: "confirmation-7",
      opinionVersion: 7,
      opinionHash: HASH_V1,
    },
  );
  return state;
}

describe("workbench review confirmation state", () => {
  it.each([
    ["version", 6, HASH_V1],
    ["hash", 7, HASH_V2],
  ])(
    "fails closed when confirmation has a mismatched opinion %s",
    (_mismatch, opinionVersion, opinionHash) => {
      let state = createWorkbenchReviewState(REVIEW_SCOPE);

      expect(
        reviewModifyConfirmationId(
          state,
          "REVIEW_REFACTOR",
          "MODIFY_WORKSPACE",
        ),
      ).toBeNull();
      expect(beginWorkbenchReviewConfirmation(state)).toBeNull();

      const opinionRequest = beginWorkbenchReviewOpinion(state);
      state = applyWorkbenchReviewOpinion(
        opinionRequest.state,
        opinionRequest.token,
        {
          version: 7,
          contentHash: HASH_V1,
        },
      );
      const confirmationRequest = beginWorkbenchReviewConfirmation(state);
      expect(confirmationRequest).not.toBeNull();

      state = applyWorkbenchReviewConfirmation(
        confirmationRequest!.state,
        confirmationRequest!.token,
        {
          confirmationId: "confirmation-mismatch",
          opinionVersion,
          opinionHash,
        },
      );

      expect(state.confirmation).toBeNull();
      expect(state.error).toEqual({
        code: "REVIEW_CONFIRMATION_INVALID_RESPONSE",
        message: "Review confirmation could not be verified",
      });
      expect(
        reviewModifyConfirmationId(
          state,
          "REVIEW_REFACTOR",
          "MODIFY_WORKSPACE",
        ),
      ).toBeNull();
    },
  );

  it("accepts only a confirmation bound to the current opinion exact version and hash", () => {
    let state = createWorkbenchReviewState(REVIEW_SCOPE);
    const opinionRequest = beginWorkbenchReviewOpinion(state);
    state = applyWorkbenchReviewOpinion(
      opinionRequest.state,
      opinionRequest.token,
      {
        version: 7,
        contentHash: HASH_V1,
      },
    );
    const confirmationRequest = beginWorkbenchReviewConfirmation(state)!;

    state = applyWorkbenchReviewConfirmation(
      confirmationRequest.state,
      confirmationRequest.token,
      {
        confirmationId: "confirmation-7",
        opinionVersion: 7,
        opinionHash: HASH_V1,
      },
    );

    expect(state.confirmation).toEqual({
      confirmationId: "confirmation-7",
      opinionVersion: 7,
      opinionHash: HASH_V1,
    });
    expect(
      reviewModifyConfirmationId(state, "REVIEW_REFACTOR", "MODIFY_WORKSPACE"),
    ).toBe("confirmation-7");
    expect(
      reviewModifyConfirmationId(state, "REVIEW_REFACTOR", "DISCUSS_READ_ONLY"),
    ).toBeNull();
    expect(
      reviewModifyConfirmationId(state, "IMPLEMENT_TEST", "MODIFY_WORKSPACE"),
    ).toBeNull();
  });

  it("invalidates confirmation when an exact opinion version or hash changes", () => {
    let state = confirmedState();
    const nextOpinion = beginWorkbenchReviewOpinion(state);
    state = applyWorkbenchReviewOpinion(nextOpinion.state, nextOpinion.token, {
      version: 8,
      contentHash: HASH_V2,
    });

    expect(state.opinion).toEqual({ version: 8, contentHash: HASH_V2 });
    expect(state.confirmation).toBeNull();
    expect(
      reviewModifyConfirmationId(state, "REVIEW_REFACTOR", "MODIFY_WORKSPACE"),
    ).toBeNull();
  });

  it("clears authorization when owner, workbench, or phase scope changes", () => {
    for (const nextScope of [
      { ...REVIEW_SCOPE, ownerId: "owner-2" },
      { ...REVIEW_SCOPE, workbenchId: "workbench-2" },
      { ...REVIEW_SCOPE, phase: "IMPLEMENT_TEST" as const },
    ]) {
      const switched = switchWorkbenchReviewScope(confirmedState(), nextScope);

      expect(switched.scope).toEqual(nextScope);
      expect(switched.opinion).toBeNull();
      expect(switched.confirmation).toBeNull();
      expect(
        reviewModifyConfirmationId(
          switched,
          "REVIEW_REFACTOR",
          "MODIFY_WORKSPACE",
        ),
      ).toBeNull();
    }
  });

  it("ignores delayed opinion and confirmation responses from obsolete requests", () => {
    let state = createWorkbenchReviewState(REVIEW_SCOPE);
    const oldOpinionRequest = beginWorkbenchReviewOpinion(state);
    const currentOpinionRequest = beginWorkbenchReviewOpinion(
      oldOpinionRequest.state,
    );

    state = applyWorkbenchReviewOpinion(
      currentOpinionRequest.state,
      oldOpinionRequest.token,
      { version: 6, contentHash: HASH_V1 },
    );
    expect(state.opinion).toBeNull();

    state = applyWorkbenchReviewOpinion(state, currentOpinionRequest.token, {
      version: 7,
      contentHash: HASH_V1,
    });
    const oldConfirmationRequest = beginWorkbenchReviewConfirmation(state)!;
    const switched = switchWorkbenchReviewScope(oldConfirmationRequest.state, {
      ...REVIEW_SCOPE,
      workbenchId: "workbench-2",
    });
    const afterDelayedConfirmation = applyWorkbenchReviewConfirmation(
      switched,
      oldConfirmationRequest.token,
      {
        confirmationId: "obsolete-confirmation",
        opinionVersion: 7,
        opinionHash: HASH_V1,
      },
    );

    expect(afterDelayedConfirmation).toBe(switched);
    expect(afterDelayedConfirmation.confirmation).toBeNull();
  });

  it("projects hostile and network failures onto fixed safe fields and revokes confirmation", () => {
    const started = beginWorkbenchReviewOpinion(confirmedState());
    const failed = failWorkbenchReviewRequest(started.state, started.token, {
      status: 409,
      code: "UNSAFE_BACKEND_CODE",
      message: "token=secret /home/private/project",
      responseBody: { apiKey: "secret" },
      stack: "sensitive stack",
    });

    expect(failed.confirmation).toBeNull();
    expect(failed.error).toEqual({
      code: "REVIEW_CONFLICT",
      message: "Review state changed; reload it before retrying",
    });
    expect(JSON.stringify(failed.error)).not.toMatch(
      /secret|home|token|apiKey|stack/i,
    );

    const retry = beginWorkbenchReviewOpinion(failed);
    const networkFailure = failWorkbenchReviewRequest(
      retry.state,
      retry.token,
      new Error("Bearer secret from /absolute/path"),
    );
    expect(networkFailure.error).toEqual({
      code: "REVIEW_REQUEST_FAILED",
      message: "Review request failed",
    });
  });

  it("rejects malformed proof values without throwing or retaining untrusted fields", () => {
    const state = createWorkbenchReviewState(REVIEW_SCOPE);
    const request = beginWorkbenchReviewOpinion(state);
    const malformed = applyWorkbenchReviewOpinion(
      request.state,
      request.token,
      {
        version: 0,
        contentHash: `NOT-SHA256-${"/home/private"}-${"secret"}`,
        responseBody: { token: "secret" },
      } as never,
    );

    expect(malformed.opinion).toBeNull();
    expect(malformed.confirmation).toBeNull();
    expect(malformed.error).toEqual({
      code: "REVIEW_OPINION_INVALID_RESPONSE",
      message: "Review opinion could not be verified",
    });
    expect(JSON.stringify(malformed)).not.toMatch(
      /home|secret|token|responseBody/i,
    );
  });
});
