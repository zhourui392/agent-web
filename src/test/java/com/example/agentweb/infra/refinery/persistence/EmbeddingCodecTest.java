package com.example.agentweb.infra.refinery.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public class EmbeddingCodecTest {

    @Test
    public void encode_then_decode_should_round_trip_losslessly() {
        float[] original = {1.5f, -2.0f, 0.0f, 3.14159f, Float.MIN_VALUE, Float.MAX_VALUE};

        byte[] bytes = EmbeddingCodec.encode(original);
        float[] decoded = EmbeddingCodec.decode(bytes);

        assertArrayEquals(original, decoded, 0f);
    }

    @Test
    public void encode_length_should_equal_dim_times_4() {
        // 每个 float 占 4 字节, IEEE 754 单精度
        float[] vec = new float[3072];
        byte[] bytes = EmbeddingCodec.encode(vec);
        assertEquals(3072 * 4, bytes.length);
    }

    @Test
    public void encode_should_use_little_endian_byte_order() {
        // float 1.0f 的 IEEE 754 表示是 0x3F800000;
        // 小端写入应为 [0x00, 0x00, 0x80, 0x3F]
        float[] vec = {1.0f};
        byte[] bytes = EmbeddingCodec.encode(vec);

        assertEquals(4, bytes.length);
        assertEquals((byte) 0x00, bytes[0]);
        assertEquals((byte) 0x00, bytes[1]);
        assertEquals((byte) 0x80, bytes[2]);
        assertEquals((byte) 0x3F, bytes[3]);
    }

    @Test
    public void decode_byte_length_not_divisible_by_4_should_throw_illegal_argument_exception() {
        byte[] bad = new byte[15];
        assertThrows(IllegalArgumentException.class, () -> EmbeddingCodec.decode(bad));
    }

    @Test
    public void encode_null_should_throw_null_pointer_exception() {
        assertThrows(NullPointerException.class, () -> EmbeddingCodec.encode(null));
    }

    @Test
    public void decode_null_should_throw_null_pointer_exception() {
        assertThrows(NullPointerException.class, () -> EmbeddingCodec.decode(null));
    }
}
