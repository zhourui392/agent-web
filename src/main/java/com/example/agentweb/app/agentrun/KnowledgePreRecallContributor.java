package com.example.agentweb.app.agentrun;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performs weighted keyword recall over workspace index files.
 *
 * <p>召回治理 (设计方案 §A3/§B1):</p>
 * <ul>
 *   <li>停用词: 2 字中文泛词 (recall-stopwords.txt) 不参与计分</li>
 *   <li>加权计分: 精确标记 (含数字/_/./:/-) 权重 3, ≥4 字中文短语 / ≥5 字符英文标识符权重 2, 其余 1</li>
 *   <li>触发收紧: 单 token 触发仅限权重 ≥2; 弱 token 需 ≥3 个不同命中</li>
 *   <li>按 index 分组: 每个 index 独立截断与输出, index 显式 top_k 优先于 policy 全局值</li>
 *   <li>inline 模式: 命中行整行直注 (确定性事实映射), pointer 模式只注入文件路径</li>
 * </ul>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Component
@Slf4j
public class KnowledgePreRecallContributor implements PromptContributor {

    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^]]+\\]\\(([^)]+)\\)");
    /** ASCII 标识符 / 纯数字错误码 (≥4 位, 最高精度信号) / 中文串. */
    private static final Pattern CODE_TOKEN =
            Pattern.compile("[A-Za-z][A-Za-z0-9_./:-]{2,}|[0-9]{4,}|[\\u4e00-\\u9fa5]{2,}");
    private static final int DEFAULT_TOP_K = 8;
    private static final int WEIGHT_PRECISE = 3;
    private static final int WEIGHT_PHRASE = 2;
    private static final int MIN_WEAK_TOKEN_HITS = 3;
    private static final Set<String> STOPWORDS = loadStopwords();

    private final WorkspaceContextResolver resolver;

    public KnowledgePreRecallContributor(WorkspaceContextResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void append(PromptAssembly assembly) {
        RunRecallPolicy policy = assembly.getContext().getRecallPolicy();
        if (!policy.isWorkspaceKnowledgeEnabled()) {
            assembly.addRecallContribution(RecallContribution.disabled(RecallChannel.WORKSPACE_KNOWLEDGE));
            return;
        }
        WorkspaceContext context = workspaceContext(assembly);
        if (context == null || context.getKnowledgeIndexes().isEmpty()) {
            assembly.addRecallContribution(RecallContribution.notApplicable(
                    RecallChannel.WORKSPACE_KNOWLEDGE, policy.getTopK()));
            return;
        }
        Set<String> keywords = keywords(assembly.getContext().getOriginalInput());
        if (keywords.isEmpty()) {
            assembly.addRecallContribution(RecallContribution.notApplicable(
                    RecallChannel.WORKSPACE_KNOWLEDGE, policy.getTopK()));
            return;
        }
        int policyTopK = policy.getTopK() <= 0 ? DEFAULT_TOP_K : policy.getTopK();
        List<RecallHit> allHits = new ArrayList<RecallHit>();
        for (WorkspaceKnowledgeIndex index : context.getKnowledgeIndexes()) {
            try {
                appendIndex(assembly, context, index, keywords, policyTopK, allHits);
            } catch (RuntimeException | IOException e) {
                log.warn("workspace-knowledge-recall-index-failed path={} reason={}",
                        index.getPath(), e.getMessage());
            }
        }
        if (allHits.isEmpty()) {
            assembly.addRecallContribution(RecallContribution.miss(
                    RecallChannel.WORKSPACE_KNOWLEDGE, policyTopK));
            return;
        }
        assembly.addRecallContribution(RecallContribution.hit(
                RecallChannel.WORKSPACE_KNOWLEDGE, policyTopK, allHits));
    }

    private void appendIndex(PromptAssembly assembly, WorkspaceContext context,
                             WorkspaceKnowledgeIndex index, Set<String> keywords,
                             int policyTopK, List<RecallHit> allHits) throws IOException {
        int topK = index.getTopK() > 0 ? index.getTopK() : policyTopK;
        List<Candidate> candidates = matchIndex(context, index, keywords);
        List<Candidate> top = topCandidates(candidates, topK);
        if (top.isEmpty()) {
            return;
        }
        if (index.getMode() == WorkspaceKnowledgeIndex.Mode.INLINE) {
            appendInlinePart(assembly, index, top, allHits);
        } else {
            appendPointerPart(assembly, top, allHits);
        }
    }

    private void appendPointerPart(PromptAssembly assembly, List<Candidate> top,
                                   List<RecallHit> allHits) {
        List<String> paths = new ArrayList<String>();
        for (Candidate candidate : top) {
            paths.add(candidate.payload);
            assembly.addWorkspaceKnowledgeHit(candidate.payload);
            allHits.add(new RecallHit(candidate.payload, "DOC_INDEX", candidate.payload, allHits.size() + 1));
        }
        assembly.addPart(PromptPartType.KNOWLEDGE_PRE_RECALL, "Workspace Knowledge Candidates",
                formatPointerHits(paths));
    }

    private void appendInlinePart(PromptAssembly assembly, WorkspaceKnowledgeIndex index,
                                  List<Candidate> top, List<RecallHit> allHits) {
        StringBuilder sb = new StringBuilder();
        sb.append("[工作区事实·").append(index.getName()).append("]\n");
        sb.append("以下事实行来自 ").append(index.getRelativePath())
                .append("，为确定性映射（服务/psaId/库表等），可直接使用：\n");
        for (Candidate candidate : top) {
            sb.append(candidate.payload).append('\n');
            allHits.add(new RecallHit(index.getRelativePath() + "#L" + candidate.lineNo,
                    "FACT_LINE", index.getRelativePath(), allHits.size() + 1));
        }
        assembly.addPart(PromptPartType.KNOWLEDGE_PRE_RECALL,
                "Workspace Facts: " + index.getName(), sb.toString());
    }

    private WorkspaceContext workspaceContext(PromptAssembly assembly) {
        WorkspaceContext context = assembly.getContext().getWorkspaceContext();
        if (context != null || resolver == null) {
            return context;
        }
        context = resolver.resolve(assembly.getContext().getWorkingDir());
        assembly.setContext(assembly.getContext().withWorkspaceContext(context));
        return context;
    }

    private Set<String> keywords(String input) {
        Set<String> result = new LinkedHashSet<String>();
        if (input == null) {
            return result;
        }
        Matcher matcher = CODE_TOKEN.matcher(input);
        while (matcher.find()) {
            String token = matcher.group();
            addKeyword(result, token);
            addChineseNgrams(result, token);
        }
        return result;
    }

    private void addKeyword(Set<String> result, String token) {
        String lower = token.toLowerCase();
        if (lower.length() >= 2 && !STOPWORDS.contains(lower)) {
            result.add(lower);
        }
    }

    private void addChineseNgrams(Set<String> result, String token) {
        if (token == null || !token.matches("[\\u4e00-\\u9fa5]+")) {
            return;
        }
        for (int size = 2; size <= 4; size++) {
            if (token.length() < size) {
                continue;
            }
            for (int i = 0; i <= token.length() - size; i++) {
                addKeyword(result, token.substring(i, i + size));
            }
        }
    }

    /** 关键词权重: 精确标记 3 > 长短语/长标识符 2 > 弱 token 1. */
    private static int weight(String keyword) {
        if (containsPreciseMarker(keyword)) {
            return WEIGHT_PRECISE;
        }
        boolean cjk = keyword.matches("[\\u4e00-\\u9fa5]+");
        if ((cjk && keyword.length() >= 4) || (!cjk && keyword.length() >= 5)) {
            return WEIGHT_PHRASE;
        }
        return 1;
    }

    private static boolean containsPreciseMarker(String keyword) {
        return keyword.matches(".*[0-9_./:-].*");
    }

    private List<Candidate> matchIndex(WorkspaceContext context,
                                       WorkspaceKnowledgeIndex index,
                                       Set<String> keywords) throws IOException {
        List<Candidate> candidates = new ArrayList<Candidate>();
        if (!Files.isRegularFile(index.getPath())) {
            return candidates;
        }
        List<String> lines = Files.readAllLines(index.getPath(), StandardCharsets.UTF_8);
        boolean inline = index.getMode() == WorkspaceKnowledgeIndex.Mode.INLINE;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            LineMatch match = matchLine(line, keywords);
            if (!match.strong) {
                continue;
            }
            String payload = inline ? line : extractPath(context, index, line);
            if (payload != null) {
                candidates.add(new Candidate(payload, match.score, i));
            }
        }
        return candidates;
    }

    /**
     * 逐行匹配: 汇总命中关键词的加权分, 并按收紧规则判定是否足以触发——
     * 任一权重 ≥2 的 token 命中即触发, 否则需 ≥{@value MIN_WEAK_TOKEN_HITS} 个不同弱 token.
     */
    private LineMatch matchLine(String line, Set<String> keywords) {
        if (line == null || line.trim().isEmpty()) {
            return LineMatch.MISS;
        }
        String lower = line.toLowerCase();
        int score = 0;
        int weakHits = 0;
        boolean strongToken = false;
        for (String keyword : keywords) {
            if (!lower.contains(keyword)) {
                continue;
            }
            int w = weight(keyword);
            score += w;
            if (w >= WEIGHT_PHRASE) {
                strongToken = true;
            } else {
                weakHits++;
            }
        }
        boolean strong = strongToken || weakHits >= MIN_WEAK_TOKEN_HITS;
        return new LineMatch(strong, score);
    }

    private String extractPath(WorkspaceContext context, WorkspaceKnowledgeIndex index, String line) {
        Matcher matcher = MARKDOWN_LINK.matcher(line);
        String target = matcher.find() ? matcher.group(1) : null;
        if (target == null || target.trim().isEmpty()) {
            return null;
        }
        Path baseDir = index.getPath().getParent();
        Path resolved = baseDir.resolve(target.trim()).normalize();
        if (!resolved.startsWith(context.getWorkspaceRoot())) {
            return null;
        }
        return context.getWorkspaceRoot().relativize(resolved).toString().replace('\\', '/');
    }

    private List<Candidate> topCandidates(List<Candidate> candidates, int topK) {
        candidates.sort(Comparator
                .comparingInt(Candidate::getScore).reversed()
                .thenComparingInt(Candidate::getLineNo)
                .thenComparing(Candidate::getPayload));
        List<Candidate> result = new ArrayList<Candidate>();
        Set<String> seen = new HashSet<String>();
        for (Candidate candidate : candidates) {
            if (!seen.add(candidate.payload)) {
                continue;
            }
            result.add(candidate);
            if (result.size() >= topK) {
                break;
            }
        }
        return result;
    }

    private String formatPointerHits(List<String> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("[候选知识]\n");
        sb.append("以下条目只是检索命中的候选线索，不是结论：\n");
        for (String hit : hits) {
            sb.append("- ").append(hit).append('\n');
        }
        sb.append("\n请先判断是否相关；若相关，读取原文并用代码、日志或数据验证后再给结论。");
        return sb.toString();
    }

    private static Set<String> loadStopwords() {
        Set<String> stopwords = new HashSet<String>();
        try (InputStream in = KnowledgePreRecallContributor.class.getClassLoader()
                .getResourceAsStream("recall-stopwords.txt")) {
            if (in == null) {
                return stopwords;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim();
                if (!word.isEmpty() && !word.startsWith("#")) {
                    stopwords.add(word.toLowerCase());
                }
            }
        } catch (IOException e) {
            // 停用词加载失败按空表降级, 只影响降噪不影响召回
        }
        return stopwords;
    }

    private static final class LineMatch {
        static final LineMatch MISS = new LineMatch(false, 0);
        final boolean strong;
        final int score;

        LineMatch(boolean strong, int score) {
            this.strong = strong;
            this.score = score;
        }
    }

    private static final class Candidate {
        private final String payload;
        private final int score;
        private final int lineNo;

        private Candidate(String payload, int score, int lineNo) {
            this.payload = payload;
            this.score = score;
            this.lineNo = lineNo;
        }

        int getScore() {
            return score;
        }

        int getLineNo() {
            return lineNo;
        }

        String getPayload() {
            return payload;
        }
    }
}
