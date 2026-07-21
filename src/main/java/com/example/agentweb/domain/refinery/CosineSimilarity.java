package com.example.agentweb.domain.refinery;

/**
 * 余弦相似度计算工具. refinery 召回路径用此工具在内存里算 query embedding 与每条 chunk
 * embedding 的相似度, 起步阶段 (< 1 万 chunk) O(N) 扫表足够.
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public final class CosineSimilarity {

    private CosineSimilarity() {
    }

    /**
     * 计算两个向量的余弦相似度. 返回值范围 [-1, 1], 1 表示同向, 0 表示正交, -1 表示反向.
     *
     * @param a 第一个向量, 维度必须与 b 一致
     * @param b 第二个向量
     * @return 余弦相似度
     * @throws IllegalArgumentException 维度不一致或任一向量是零向量
     */
    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "dimension mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0d;
        double normA = 0d;
        double normB = 0d;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0d || normB == 0d) {
            throw new IllegalArgumentException(
                    "zero-norm vector has no direction; embedding model output is invalid");
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
