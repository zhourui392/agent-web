import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const currentDir = path.dirname(fileURLToPath(import.meta.url));
const projectDir = path.resolve(currentDir, "../..");
const script = path.join(projectDir, "scripts/harness-m4/verify-online-provider.sh");

function run(extraEnvironment: NodeJS.ProcessEnv = {}) {
  return spawnSync("bash", [script], {
    cwd: projectDir,
    encoding: "utf8",
    env: {
      PATH: process.env.PATH,
      ...extraEnvironment,
    },
  });
}

describe("Harness M4 online Provider verifier", () => {
  it("fails closed when the credential reference is absent", () => {
    const result = run();

    expect(result.status).toBe(2);
    expect(result.stderr).toContain("credential reference is required");
  });

  it("fails closed when the referenced credential is unavailable", () => {
    const result = run({
      AGENT_HARNESS_CODEX_CREDENTIAL_REFERENCE: "HARNESS_MISSING_PROVIDER_KEY",
    });

    expect(result.status).toBe(2);
    expect(result.stderr).toContain("referenced provider credential is unavailable");
  });

  it("rejects an invalid environment variable reference", () => {
    const result = run({
      AGENT_HARNESS_CODEX_CREDENTIAL_REFERENCE: "INVALID-REFERENCE",
    });

    expect(result.status).toBe(2);
    expect(result.stderr).toContain("credential reference is invalid");
  });
});
