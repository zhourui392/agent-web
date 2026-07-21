package com.example.agentweb.infra.refinery.persistence;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Objects;

/**
 * 嵌入向量 BLOB 编解码. chat_rag_chunk.embedding 列以小端字节序 (IEEE 754 单精度) 存储,
 * 选择小端的原因是与 numpy / protobuf 等主流 ML 生态默认序列化方式一致, 未来跨语言读写无需翻译.
 *
 * <p>BLOB 长度恒为 dim * 4 字节, 例如 3072 维 → 12288 字节.
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public final class EmbeddingCodec {

    private EmbeddingCodec() {
    }

    /**
     * 把 float[] 序列化为小端字节数组.
     *
     * @param vector 嵌入向量, 不能为 null
     * @return dim * 4 字节, 小端字节序
     */
    public static byte[] encode(float[] vector) {
        Objects.requireNonNull(vector, "vector");
        ByteBuffer buf = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buf.putFloat(value);
        }
        return buf.array();
    }

    /**
     * 反序列化字节数组为 float[]. 字节数必须被 4 整除.
     *
     * @param bytes 小端字节, 长度必须是 4 的倍数
     * @return float[], 长度 = bytes.length / 4
     * @throws IllegalArgumentException 字节数不能被 4 整除
     */
    public static float[] decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length % 4 != 0) {
            throw new IllegalArgumentException(
                    "embedding byte length not divisible by 4: " + bytes.length);
        }
        FloatBuffer fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] result = new float[bytes.length / 4];
        fb.get(result);
        return result;
    }
}
