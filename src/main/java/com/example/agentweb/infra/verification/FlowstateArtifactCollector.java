package com.example.agentweb.infra.verification;

import com.example.agentweb.adapter.verification.CollectedArtifact;
import com.example.agentweb.adapter.verification.CollectedVerification;
import com.example.agentweb.adapter.verification.VerificationArtifactCollector;
import com.example.agentweb.domain.verification.VerificationOutcome;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * flowstate 验证工件采集器:从 worktree 收集 .flowstate / failed_cases / verification-record,
 * 并把 dianxiaoer 状态词汇翻译成平台中性 {@link VerificationOutcome}(防腐边界)。
 *
 * <p>任何单文件读取/解析失败均不中断整体采集,collect 永不抛异常——降级信息经
 * degradeReason 回传,由 app 编排按 run 退出码兜底。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
public class FlowstateArtifactCollector implements VerificationArtifactCollector {

    private static final String KIND_FLOWSTATE = "FLOWSTATE";
    private static final String KIND_FAILED_CASES = "FAILED_CASES";
    private static final String KIND_VERIFICATION_RECORD = "VERIFICATION_RECORD";

    private static final String FLOWSTATE_FILE = ".flowstate";
    private static final String LEGACY_FLOWSTATE_FILE = ".superspec-state";
    private static final String STATE_KEY = "state";

    /** 内容内联上限(字节);超限工件改存平台侧文件路径 */
    private final long inlineContentLimitBytes;

    public FlowstateArtifactCollector(long inlineContentLimitBytes) {
        this.inlineContentLimitBytes = inlineContentLimitBytes;
    }

    /**
     * 采集验证工件。
     *
     * @param worktreePath 需求 worktree 根路径
     * @return 采集结果(永不为 null、永不抛异常)
     */
    @Override
    public CollectedVerification collect(String worktreePath) {
        List<CollectedArtifact> artifacts = new ArrayList<>();
        try {
            Path worktree = Paths.get(worktreePath);
            FlowstateTranslation translation = collectFlowstate(worktree, artifacts);
            collectChangeArtifacts(worktree, artifacts);
            return new CollectedVerification(translation.outcome(), artifacts, translation.degradeReason());
        } catch (RuntimeException e) {
            log.warn("验证工件采集异常, 返回已采集部分: worktreePath={}", worktreePath, e);
            return new CollectedVerification(null, artifacts, "采集异常: " + e.getMessage());
        }
    }

    /**
     * 读取并翻译 .flowstate(缺失时回退旧文件名 .superspec-state)。
     *
     * @param worktree  worktree 根路径
     * @param artifacts 工件收集器(读到的原文会追加进去)
     * @return 翻译结果(含降级原因)
     */
    private FlowstateTranslation collectFlowstate(Path worktree, List<CollectedArtifact> artifacts) {
        Path file = worktree.resolve(FLOWSTATE_FILE);
        if (!Files.isRegularFile(file)) {
            file = worktree.resolve(LEGACY_FLOWSTATE_FILE);
        }
        if (!Files.isRegularFile(file)) {
            return new FlowstateTranslation(null, "flowstate 文件缺失");
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            // 解析失败时原文仍入 artifacts:原文有排查价值
            artifacts.add(toArtifact(KIND_FLOWSTATE, bytes, file));
            return translate(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("读取 flowstate 文件失败: {}", file, e);
            return new FlowstateTranslation(null, "flowstate 文件读取失败: " + e.getMessage());
        }
    }

    /**
     * 把 flowstate YAML 的 state 键翻译成平台中性终态。
     *
     * @param yamlText flowstate 原文
     * @return 翻译结果;解析失败/无法映射时 outcome=null + degradeReason
     */
    private FlowstateTranslation translate(String yamlText) {
        Object parsed;
        try {
            parsed = new Yaml().load(yamlText);
        } catch (RuntimeException e) {
            return new FlowstateTranslation(null, "flowstate YAML 解析失败: " + e.getMessage());
        }
        Object stateValue = parsed instanceof Map ? ((Map<?, ?>) parsed).get(STATE_KEY) : null;
        VerificationOutcome outcome = mapState(stateValue == null ? null : stateValue.toString());
        if (outcome == null) {
            return new FlowstateTranslation(null, "flowstate state 无法映射: " + stateValue);
        }
        return new FlowstateTranslation(outcome, null);
    }

    /**
     * dianxiaoer 状态词汇 → 平台中性终态映射(防腐)。
     *
     * @param state flowstate state 原值
     * @return 映射结果;未知/缺失返回 null
     */
    private VerificationOutcome mapState(String state) {
        if (state == null) {
            return null;
        }
        switch (state) {
            case "SWIMLANE_VERIFIED":
            case "SUBMITTED_FOR_TEST":
            case "READY_FOR_ARCHIVE":
            case "ARCHIVED":
                return VerificationOutcome.VERIFIED;
            case "DEPLOY_FAILED":
                return VerificationOutcome.DEPLOY_FAILED;
            case "VERIFY_FAILED":
            case "VERIFY_BLOCKED":
            case "VERIFY_WARNING":
                return VerificationOutcome.BLOCKED;
            default:
                return null;
        }
    }

    /**
     * 遍历 openspec/changes/* 目录收集 failed_cases / verification-record 工件。
     *
     * @param worktree  worktree 根路径
     * @param artifacts 工件收集器
     */
    private void collectChangeArtifacts(Path worktree, List<CollectedArtifact> artifacts) {
        Path changesDir = worktree.resolve("openspec").resolve("changes");
        if (!Files.isDirectory(changesDir)) {
            return;
        }
        for (Path change : listSorted(changesDir, "*")) {
            if (Files.isDirectory(change)) {
                collectFromChangeDir(change, artifacts);
            }
        }
    }

    /**
     * 采集单个 change 目录下的工件。
     *
     * @param change    change 目录
     * @param artifacts 工件收集器
     */
    private void collectFromChangeDir(Path change, List<CollectedArtifact> artifacts) {
        Path failedCases = change.resolve("failed_cases.json");
        if (Files.isRegularFile(failedCases)) {
            readInto(KIND_FAILED_CASES, failedCases, artifacts);
        }
        for (Path record : listSorted(change, "verification-record*.md")) {
            if (Files.isRegularFile(record)) {
                readInto(KIND_VERIFICATION_RECORD, record, artifacts);
            }
        }
    }

    /**
     * 按名称排序列出目录下匹配 glob 的条目(保证采集顺序稳定)。
     *
     * @param dir  目录
     * @param glob 文件名 glob
     * @return 排序后的条目列表;目录读取失败返回空列表
     */
    private List<Path> listSorted(Path dir, String glob) {
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            for (Path entry : stream) {
                entries.add(entry);
            }
        } catch (IOException e) {
            log.warn("遍历目录失败: {}", dir, e);
        }
        entries.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return entries;
    }

    /**
     * 读取单个工件文件并追加到收集器;IO 失败仅告警不中断。
     *
     * @param kind      工件类型
     * @param file      工件文件
     * @param artifacts 工件收集器
     */
    private void readInto(String kind, Path file, List<CollectedArtifact> artifacts) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            artifacts.add(toArtifact(kind, bytes, file));
        } catch (IOException e) {
            log.warn("读取工件失败: kind={}, file={}", kind, file, e);
        }
    }

    /**
     * 按内联上限决定 content 内联还是落文件路径。
     *
     * @param kind  工件类型
     * @param bytes 文件原始字节(按 UTF-8 解码)
     * @param file  文件路径
     * @return 工件对象
     */
    private CollectedArtifact toArtifact(String kind, byte[] bytes, Path file) {
        if (bytes.length <= inlineContentLimitBytes) {
            return new CollectedArtifact(kind, new String(bytes, StandardCharsets.UTF_8), null);
        }
        return new CollectedArtifact(kind, null, file.toAbsolutePath().toString());
    }

    /**
     * flowstate 翻译结果:终态 + 降级原因(二者互斥)。
     *
     * @param outcome       平台中性终态;无法翻译时为 null
     * @param degradeReason 降级原因;翻译成功时为 null
     */
    private record FlowstateTranslation(VerificationOutcome outcome, String degradeReason) {
    }
}
