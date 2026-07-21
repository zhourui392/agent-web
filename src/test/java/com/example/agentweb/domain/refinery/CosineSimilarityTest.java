package com.example.agentweb.domain.refinery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public class CosineSimilarityTest {

    private static final double EPS = 1e-6;

    @Test
    public void identical_unit_vectors_return_one() {
        float[] a = {1f, 0f, 0f};
        float[] b = {1f, 0f, 0f};
        assertEquals(1.0, CosineSimilarity.cosine(a, b), EPS);
    }

    @Test
    public void orthogonal_vectors_return_zero() {
        float[] a = {1f, 0f, 0f};
        float[] b = {0f, 1f, 0f};
        assertEquals(0.0, CosineSimilarity.cosine(a, b), EPS);
    }

    @Test
    public void opposite_vectors_return_negative_one() {
        float[] a = {1f, 0f, 0f};
        float[] b = {-1f, 0f, 0f};
        assertEquals(-1.0, CosineSimilarity.cosine(a, b), EPS);
    }

    @Test
    public void same_direction_different_magnitude_return_one() {
        // 余弦只看夹角, 不看长度: [3,4,0] 和 [6,8,0] 都是 (3,4,0) 方向
        float[] a = {3f, 4f, 0f};
        float[] b = {6f, 8f, 0f};
        assertEquals(1.0, CosineSimilarity.cosine(a, b), EPS);
    }

    @Test
    public void dimension_mismatch_throws_illegal_argument() {
        float[] a = {1f, 0f};
        float[] b = {1f, 0f, 0f};
        assertThrows(IllegalArgumentException.class, () -> CosineSimilarity.cosine(a, b));
    }

    @Test
    public void zero_norm_throws_illegal_argument() {
        // 零向量没有方向, 余弦无定义; 嵌入模型不应输出零向量, 视为脏数据 fail-fast
        float[] zero = {0f, 0f, 0f};
        float[] nonZero = {1f, 1f, 1f};
        assertThrows(IllegalArgumentException.class, () -> CosineSimilarity.cosine(zero, nonZero));
        assertThrows(IllegalArgumentException.class, () -> CosineSimilarity.cosine(nonZero, zero));
    }
}
