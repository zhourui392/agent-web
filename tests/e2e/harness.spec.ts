import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs/promises';
import * as os from 'os';
import * as path from 'path';

type HarnessRun = {
  runId: string;
  status: string;
  stages: Array<{
    stage: string;
    status: string;
    attempts: Array<{ number: number }>;
  }>;
  artifacts: Array<{ artifactType: string; stage: string; attempt: number }>;
};

const tempRepos: string[] = [];

test.afterEach(async () => {
  while (tempRepos.length > 0) {
    await fs.rm(tempRepos.pop()!, { recursive: true, force: true });
  }
});

test('阶段对话可直接启动 Codex 并用新 Attempt 连续修订', async ({ page, request }) => {
  const repository = await createGitRepository('harness-conversation');
  await page.goto('/admin/harness.html');
  const runId = await createRun(page, repository, `M4 Conversation ${Date.now()}`);

  await selectRunAndStage(page, runId, 'ANALYSIS');
  await page.getByTestId('harness-conversation-input').fill('请先梳理需求与可观察验收标准');
  await page.getByTestId('harness-send-conversation').click();

  await expect.poll(async () => stage(await getRun(request, runId), 'ANALYSIS')
    .attempts.at(-1)?.number).toBe(1);
  await expect.poll(async () => runtimeStatus(request, runId, 'ANALYSIS', 1), {
    timeout: 20_000,
  }).toBe('SUCCEEDED');
  await expect.poll(async () => {
    const response = await request.get(`/api/harness/runs/${encodeURIComponent(runId)}/conversation`);
    const messages = await response.json();
    return messages.filter((item: { role: string }) => item.role === 'ASSISTANT').length;
  }, { timeout: 20_000 }).toBe(1);

  await selectRunAndStage(page, runId, 'ANALYSIS');
  await expect(page.getByTestId('harness-conversation-assistant')).toContainText('REQ-1');
  await page.getByTestId('harness-conversation-input').fill('再补充失败路径和并发边界');
  await page.getByTestId('harness-send-conversation').click();

  await expect.poll(async () => stage(await getRun(request, runId), 'ANALYSIS')
    .attempts.at(-1)?.number).toBe(2);
  await expect.poll(async () => runtimeStatus(request, runId, 'ANALYSIS', 2), {
    timeout: 20_000,
  }).toBe('SUCCEEDED');
  await selectRunAndStage(page, runId, 'ANALYSIS');
  await expect(page.getByTestId('harness-conversation-user')).toHaveCount(2);
  await expect(page.getByTestId('harness-conversation-assistant')).toHaveCount(2);
});

test('四阶段页面 Happy Path 包含 WAITING_INPUT、local 部署和最终报告', async ({ page, request }) => {
  const repository = await createGitRepository('harness-happy');
  await page.goto('/admin/harness.html');
  await expect(page.getByText('MVP 安全边界：仅 local、单 Codex Runtime、只读 MCP')).toBeVisible();
  await expect(page.getByText(/test \/ production 部署明确禁止/)).toBeVisible();

  const runId = await createRun(page, repository, `M4 Happy ${Date.now()}`);
  await completeAgentStage(page, request, runId, 'ANALYSIS', '分析受控 E2E 需求', true);
  await completeAgentStage(page, request, runId, 'DESIGN', '设计 DDD 四层方案');
  await completeAgentStage(page, request, runId, 'IMPLEMENTATION', '按 TDD 实现并留下 RED/GREEN 证据');
  await completeDeploymentStage(page, request, runId);

  await expect.poll(async () => (await getRun(request, runId)).status).toBe('COMPLETED');
  await selectRunAndStage(page, runId, 'DEPLOYMENT');
  await expect(page.getByTestId('harness-final-report')).toBeVisible();
  const reportResponse = await request.get(`/api/harness/runs/${encodeURIComponent(runId)}/report`);
  expect(reportResponse.ok()).toBeTruthy();
  const report = await reportResponse.json();
  expect(report.traceabilityComplete).toBe(true);
  expect(report.traceability[0]).toMatchObject({
    requirementId: 'REQ-1',
    acceptanceCriteriaId: 'AC-1',
    implementationRef: 'src/main/A.java',
    deploymentPassed: true,
  });
});

test('Gate 失败后新 Attempt 修订，且上游重试使已通过下游失效', async ({ page, request }) => {
  const repository = await createGitRepository('harness-retry');
  await page.goto('/admin/harness.html');
  const runId = await createRun(page, repository, `M4 Retry ${Date.now()}`);

  await startAndRunStage(page, request, runId, 'ANALYSIS', '[E2E_GATE_FAIL] 生成缺少 REQ ID 的修订');
  await selectRunAndStage(page, runId, 'ANALYSIS');
  await page.getByRole('tab', { name: 'Artifact 与 Gate' }).click();
  await page.getByTestId('harness-run-gates').click();
  await expect.poll(async () => stage(await getRun(request, runId), 'ANALYSIS').status)
    .toBe('FAILED');

  await selectRunAndStage(page, runId, 'ANALYSIS');
  await expect(page.getByTestId('harness-gate-failure'))
    .toContainText('requirement-ids-unique');
  await page.getByTestId('harness-stage-retry').click();
  await page.getByRole('button', { name: /^(确定|OK)$/ }).click();
  await expect.poll(async () => stage(await getRun(request, runId), 'ANALYSIS').attempts.at(-1)?.number)
    .toBe(2);
  await completeRunningStage(page, request, runId, 'ANALYSIS', '修订为完整 REQ-1 与 AC-1');
  await completeAgentStage(page, request, runId, 'DESIGN', '生成已批准设计与追踪矩阵');

  await selectRunAndStage(page, runId, 'ANALYSIS');
  await page.getByTestId('harness-stage-retry').click();
  await page.getByRole('button', { name: /^(确定|OK)$/ }).click();

  await expect.poll(async () => {
    const run = await getRun(request, runId);
    return `${stage(run, 'ANALYSIS').status}/${stage(run, 'DESIGN').status}`;
  }).toBe('RUNNING/INVALIDATED');
  await expect.poll(async () => stage(await getRun(request, runId), 'ANALYSIS').attempts.at(-1)?.number)
    .toBe(3);
  await selectRunAndStage(page, runId, 'DESIGN');
  await expect(page.getByTestId('harness-stage-design')).toContainText('已失效');
});

async function createGitRepository(prefix: string): Promise<string> {
  const repository = await fs.mkdtemp(path.join(os.tmpdir(), `${prefix}-`));
  tempRepos.push(repository);
  execFileSync('git', ['init'], { cwd: repository, stdio: 'ignore' });
  execFileSync('git', ['config', 'user.email', 'harness-e2e@example.invalid'], { cwd: repository });
  execFileSync('git', ['config', 'user.name', 'Harness E2E'], { cwd: repository });
  await fs.writeFile(path.join(repository, 'README.md'), '# Harness E2E\n', 'utf8');
  execFileSync('git', ['add', 'README.md'], { cwd: repository });
  execFileSync('git', ['commit', '-m', 'baseline'], { cwd: repository, stdio: 'ignore' });
  return repository;
}

async function createRun(page: Page, repository: string, title: string): Promise<string> {
  await page.getByTestId('harness-create-run').click();
  await page.getByTestId('harness-create-title').fill(title);
  await page.getByTestId('harness-create-working-dir').fill(repository);
  await page.getByTestId('harness-create-requirement').fill(
    'REQ-1: 用 Harness 完成受控 local 交付，并生成 AC-1 的完整追踪证据。');
  await page.getByTestId('harness-submit-create').click();
  await expect(page.getByRole('dialog', { name: '新建 Harness Run' })).toBeHidden();
  const row = page.getByTestId('harness-run-row').filter({ hasText: title });
  await expect(row).toBeVisible();
  const runId = await row.getAttribute('data-run-id');
  expect(runId).toBeTruthy();
  return runId!;
}

async function completeAgentStage(page: Page, request: APIRequestContext, runId: string,
                                  stageName: string, input: string,
                                  withQuestion = false): Promise<void> {
  await selectRunAndStage(page, runId, stageName);
  await page.getByTestId('harness-stage-start').click();
  await expect.poll(async () => stage(await getRun(request, runId), stageName).status).toBe('RUNNING');
  if (withQuestion) {
    const attemptBefore = stage(await getRun(request, runId), stageName).attempts.at(-1)?.number;
    await page.getByTestId('harness-ask-question').click();
    const questionDialog = page.getByRole('dialog', { name: '登记当前 Attempt 补充问题' });
    await questionDialog.locator('textarea').fill('请确认只允许 local 部署？');
    await questionDialog.getByRole('button', { name: '提交' }).click();
    await expect.poll(async () => stage(await getRun(request, runId), stageName).status)
      .toBe('WAITING_INPUT');
    await selectRunAndStage(page, runId, stageName);
    await page.getByRole('tab', { name: '补充输入' }).click();
    const question = page.getByTestId('harness-question');
    await question.locator('textarea').fill('确认：仅 local，禁止 test/production。');
    await question.getByTestId('harness-answer-question').click();
    await expect.poll(async () => stage(await getRun(request, runId), stageName).status)
      .toBe('RUNNING');
    expect(stage(await getRun(request, runId), stageName).attempts.at(-1)?.number)
      .toBe(attemptBefore);
  }
  await completeRunningStage(page, request, runId, stageName, input);
}

async function completeRunningStage(page: Page, request: APIRequestContext, runId: string,
                                    stageName: string, input: string): Promise<void> {
  await selectRunAndStage(page, runId, stageName);
  await page.getByTestId('harness-current-input').fill(input);
  if (stageName === 'IMPLEMENTATION') {
    await formInput(page, '可写逻辑根').fill('workspace');
    await formInput(page, '逻辑命令白名单').fill('mvn-test');
  }
  await page.getByTestId('harness-resolve-snapshot').click();
  await expect(page.getByText('Capability Snapshot 已固化')).toBeVisible();
  await page.getByTestId('harness-launch-runtime').click();
  const attempt = stage(await getRun(request, runId), stageName).attempts.at(-1)!.number;
  await expect.poll(async () => runtimeStatus(request, runId, stageName, attempt), {
    timeout: 20_000,
  }).toBe('SUCCEEDED');
  await expect.poll(async () => {
    const run = await getRun(request, runId);
    return run.artifacts.filter(item => item.stage === stageName && item.attempt === attempt).length;
  }, { timeout: 20_000 }).toBeGreaterThan(0);

  await selectRunAndStage(page, runId, stageName);
  await page.getByRole('tab', { name: 'Artifact 与 Gate' }).click();
  await page.getByTestId('harness-run-gates').click();
  await expect.poll(async () => stage(await getRun(request, runId), stageName).status)
    .toBe('RUNNING');
  await page.getByTestId('harness-request-approval').click();
  await expect.poll(async () => stage(await getRun(request, runId), stageName).status)
    .toBe('WAITING_APPROVAL');
  await selectRunAndStage(page, runId, stageName);
  await page.getByRole('tab', { name: 'Artifact 与 Gate' }).click();
  await page.getByTestId('harness-approve').click();
  const approvalDialog = page.getByRole('dialog', { name: '批准当前 Artifact 基线' });
  await approvalDialog.locator('textarea').fill('E2E 批准当前 Hash');
  await approvalDialog.getByTestId('harness-submit-approval').click();
  await expect.poll(async () => stage(await getRun(request, runId), stageName).status).toBe('PASSED');
}

async function startAndRunStage(page: Page, request: APIRequestContext, runId: string,
                                stageName: string, input: string): Promise<void> {
  await selectRunAndStage(page, runId, stageName);
  await page.getByTestId('harness-stage-start').click();
  await expect.poll(async () => stage(await getRun(request, runId), stageName).status).toBe('RUNNING');
  await selectRunAndStage(page, runId, stageName);
  await page.getByTestId('harness-current-input').fill(input);
  await page.getByTestId('harness-resolve-snapshot').click();
  await page.getByTestId('harness-launch-runtime').click();
  const attempt = stage(await getRun(request, runId), stageName).attempts.at(-1)!.number;
  await expect.poll(async () => runtimeStatus(request, runId, stageName, attempt), {
    timeout: 20_000,
  }).toBe('SUCCEEDED');
}

async function completeDeploymentStage(page: Page, request: APIRequestContext,
                                       runId: string): Promise<void> {
  await selectRunAndStage(page, runId, 'DEPLOYMENT');
  await page.getByTestId('harness-stage-start').click();
  await expect.poll(async () => stage(await getRun(request, runId), 'DEPLOYMENT').status)
    .toBe('RUNNING');
  await selectRunAndStage(page, runId, 'DEPLOYMENT');
  await page.getByRole('tab', { name: '部署' }).click();
  await page.getByTestId('harness-deployment-approval').click();
  const messageBox = page.locator('.el-message-box');
  await messageBox.getByRole('textbox').fill('E2E 独立批准当前 local 输入 Hash');
  await messageBox.getByRole('button', { name: /^(确定|OK)$/ }).click();
  await expect.poll(async () => {
    const response = await request.get(
      `/api/harness/runs/${encodeURIComponent(runId)}/stages/DEPLOYMENT/deployment-readiness`);
    return response.ok() ? (await response.json()).approved : false;
  }).toBe(true);

  await selectRunAndStage(page, runId, 'DEPLOYMENT');
  await page.getByRole('tab', { name: '部署' }).click();
  await page.getByTestId('harness-start-deployment').click();
  const deploymentDialog = page.getByRole('dialog', { name: '执行受控 local 部署' });
  await expect(deploymentDialog.getByTestId('harness-deployment-template')).toHaveValue('local-default');
  await page.getByTestId('harness-submit-deployment').click();
  await expect.poll(async () => {
    const response = await request.get(`/api/harness/runs/${encodeURIComponent(runId)}/deployments`);
    const values = await response.json();
    return values.at(-1)?.status;
  }, { timeout: 20_000 }).toBe('SUCCEEDED');

  await selectRunAndStage(page, runId, 'DEPLOYMENT');
  await page.getByRole('tab', { name: 'Artifact 与 Gate' }).click();
  await page.getByTestId('harness-run-gates').click();
  await page.getByTestId('harness-request-approval').click();
  await expect.poll(async () => stage(await getRun(request, runId), 'DEPLOYMENT').status)
    .toBe('WAITING_APPROVAL');
  await selectRunAndStage(page, runId, 'DEPLOYMENT');
  await page.getByRole('tab', { name: 'Artifact 与 Gate' }).click();
  await page.getByTestId('harness-approve').click();
  const approvalDialog = page.getByRole('dialog', { name: '批准当前 Artifact 基线' });
  await approvalDialog.locator('textarea').fill('E2E 确认最终交付');
  await approvalDialog.getByTestId('harness-submit-approval').click();
}

async function selectRunAndStage(page: Page, runId: string, stageName: string): Promise<void> {
  await page.reload();
  const row = page.locator(`[data-test="harness-run-row"][data-run-id="${runId}"]`);
  await expect(row).toBeVisible();
  await row.click();
  await page.getByTestId(`harness-stage-${stageName.toLowerCase()}`).click();
}

async function getRun(request: APIRequestContext, runId: string): Promise<HarnessRun> {
  const response = await request.get(`/api/harness/runs/${encodeURIComponent(runId)}`);
  expect(response.ok(), await response.text()).toBeTruthy();
  return response.json();
}

function stage(run: HarnessRun, stageName: string) {
  const value = run.stages.find(item => item.stage === stageName);
  if (!value) {
    throw new Error(`missing stage ${stageName}`);
  }
  return value;
}

async function runtimeStatus(request: APIRequestContext, runId: string,
                             stageName: string, attempt: number): Promise<string> {
  const response = await request.get(`/api/harness/runs/${encodeURIComponent(runId)}/stages/`
    + `${stageName}/attempts/${attempt}/execution`);
  if (!response.ok()) {
    return `HTTP_${response.status()}`;
  }
  return (await response.json()).status;
}

function formInput(page: Page, label: string) {
  return page.locator('.el-form-item').filter({ hasText: label }).locator('input').first();
}
