import { spawnSync } from "node:child_process";
import {
  chmodSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it } from "vitest";

const currentDir = path.dirname(fileURLToPath(import.meta.url));
const projectDir = path.resolve(currentDir, "../..");
const script = path.join(projectDir, "scripts/harness-m4/verify-online-provider.sh");
const temporaryDirectories: string[] = [];

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

function createFakeCodex() {
  const directory = mkdtempSync(path.join(os.tmpdir(), "harness-online-provider-test-"));
  const command = path.join(directory, "codex");
  const invocationLog = path.join(directory, "invocations.log");
  temporaryDirectories.push(directory);
  writeFileSync(command, [
    "#!/usr/bin/env bash",
    "set -euo pipefail",
    "if [[ \"${1:-}\" == \"--version\" ]]; then",
    "  printf 'codex-cli 0.145.0\\n'",
    "  exit 0",
    "fi",
    "prompt=$(cat)",
    "stage=$(printf '%s' \"$prompt\" | sed -n 's/^stage: //p' | head -n 1)",
    "case \"$stage\" in",
    "  ANALYSIS) evidence='提取业务不变量、强一致性边界和变化点' ;;",
    "  DESIGN) evidence='保持 Interface、Application、Domain、Infrastructure 分层' ;;",
    "  IMPLEMENTATION) evidence='先按分层门禁定位业务规则，再写失败测试' ;;",
    "  DEPLOYMENT) evidence='核对批准版本、Preflight、构建与验收证据' ;;",
    "  *) printf 'unexpected stage: %s\\n' \"$stage\" >&2; exit 1 ;;",
    "esac",
    "output=''",
    "ignore_user_config=false",
    "uses_output_schema=false",
    "arguments=(\"$@\")",
    "for ((index = 0; index < ${#arguments[@]}; index++)); do",
    "  if [[ \"${arguments[$index]}\" == \"--ignore-user-config\" ]]; then",
    "    ignore_user_config=true",
    "  fi",
    "  if [[ \"${arguments[$index]}\" == \"--output-schema\" ]]; then",
    "    uses_output_schema=true",
    "  fi",
    "  if [[ \"${arguments[$index]}\" == \"--output-last-message\" ]]; then",
    "    output=${arguments[$((index + 1))]}",
    "  fi",
    "done",
    "[[ -n \"$output\" ]] || exit 1",
    "printf '%s\\t%s\\t%s\\t%s\\t%s\\t%s\\n' \"$stage\" \"${HOME:-}\" \"${CODEX_HOME:-}\" \"${OPENAI_API_KEY:-}\" \"$ignore_user_config\" \"$uses_output_schema\" >>\"$(dirname \"$0\")/invocations.log\"",
    "if [[ \"${FAKE_CODEX_OUTPUT_MODE:-valid}\" == \"extra-field\" ]]; then",
    "  printf '{\"stage\":\"%s\",\"skillEvidence\":\"%s\",\"unexpected\":true}\\n' \"$stage\" \"$evidence\" >\"$output\"",
    "else",
    "  printf '{\"stage\":\"%s\",\"skillEvidence\":\"%s\"}\\n' \"$stage\" \"$evidence\" >\"$output\"",
    "fi",
    "printf '{\"type\":\"turn.completed\"}\\n'",
  ].join("\n"));
  chmodSync(command, 0o700);
  return { command, invocationLog, directory };
}

afterEach(() => {
  temporaryDirectories.splice(0).forEach((directory) => {
    rmSync(directory, { recursive: true, force: true });
  });
});

describe("Harness M4 online Provider verifier", () => {
  it("uses the local Codex login by default without requiring a credential reference", () => {
    const fakeCodex = createFakeCodex();
    const home = path.join(fakeCodex.directory, "local-home");
    const codexHome = path.join(fakeCodex.directory, "local-codex-home");

    const result = run({
      AGENT_HARNESS_CODEX_COMMAND: fakeCodex.command,
      HOME: home,
      CODEX_HOME: codexHome,
    });

    expect(result.status).toBe(0);
    expect(result.stdout).toContain("M4 ONLINE PROVIDER PASS");
    const invocations = readFileSync(fakeCodex.invocationLog, "utf8")
      .split("\n")
      .filter((invocation) => invocation.length > 0);
    expect(invocations).toHaveLength(4);
    invocations.forEach((invocation) => {
      const [, actualHome, actualCodexHome, providerCredential, ignoresUserConfig, usesOutputSchema] = invocation.split("\t");
      expect(actualHome).toBe(home);
      expect(actualCodexHome).toBe(codexHome);
      expect(providerCredential).toBe("");
      expect(ignoresUserConfig).toBe("false");
      expect(usesOutputSchema).toBe("false");
    });
  });

  it("keeps isolated key authentication available as an explicit mode", () => {
    const fakeCodex = createFakeCodex();

    const result = run({
      AGENT_HARNESS_ONLINE_AUTH_MODE: "isolated-key",
      AGENT_HARNESS_CODEX_COMMAND: fakeCodex.command,
      AGENT_HARNESS_CODEX_CREDENTIAL_REFERENCE: "HARNESS_TEST_PROVIDER_KEY",
      HARNESS_TEST_PROVIDER_KEY: "test-provider-secret",
      HOME: path.join(fakeCodex.directory, "must-not-be-used"),
      CODEX_HOME: path.join(fakeCodex.directory, "must-not-be-used-either"),
    });

    expect(result.status).toBe(0);
    const invocations = readFileSync(fakeCodex.invocationLog, "utf8")
      .split("\n")
      .filter((invocation) => invocation.length > 0);
    expect(invocations).toHaveLength(4);
    invocations.forEach((invocation) => {
      const [, actualHome, actualCodexHome, providerCredential, ignoresUserConfig, usesOutputSchema] = invocation.split("\t");
      expect(actualHome).toMatch(/agent-web-harness-m4-online\.[^/]+\/home$/);
      expect(actualCodexHome).toBe(actualHome);
      expect(providerCredential).toBe("test-provider-secret");
      expect(ignoresUserConfig).toBe("true");
      expect(usesOutputSchema).toBe("true");
    });
  });

  it("rejects additional output fields in local login mode", () => {
    const fakeCodex = createFakeCodex();

    const result = run({
      AGENT_HARNESS_CODEX_COMMAND: fakeCodex.command,
      FAKE_CODEX_OUTPUT_MODE: "extra-field",
      HOME: path.join(fakeCodex.directory, "local-home"),
      CODEX_HOME: path.join(fakeCodex.directory, "local-codex-home"),
    });

    expect(result.status).toBe(1);
    expect(result.stderr).toContain("online Provider evidence validation failed: ANALYSIS");
  });

  it("rejects an unsupported authentication mode", () => {
    const result = run({
      AGENT_HARNESS_ONLINE_AUTH_MODE: "automatic-fallback",
    });

    expect(result.status).toBe(2);
    expect(result.stderr).toContain("online authentication mode is invalid");
  });

  it("fails closed when the isolated credential reference is absent", () => {
    const result = run({
      AGENT_HARNESS_ONLINE_AUTH_MODE: "isolated-key",
    });

    expect(result.status).toBe(2);
    expect(result.stderr).toContain("credential reference is required");
  });

  it("fails closed when the referenced isolated credential is unavailable", () => {
    const result = run({
      AGENT_HARNESS_ONLINE_AUTH_MODE: "isolated-key",
      AGENT_HARNESS_CODEX_CREDENTIAL_REFERENCE: "HARNESS_MISSING_PROVIDER_KEY",
    });

    expect(result.status).toBe(2);
    expect(result.stderr).toContain("referenced provider credential is unavailable");
  });

  it("rejects an invalid isolated environment variable reference", () => {
    const result = run({
      AGENT_HARNESS_ONLINE_AUTH_MODE: "isolated-key",
      AGENT_HARNESS_CODEX_CREDENTIAL_REFERENCE: "INVALID-REFERENCE",
    });

    expect(result.status).toBe(2);
    expect(result.stderr).toContain("credential reference is invalid");
  });
});
