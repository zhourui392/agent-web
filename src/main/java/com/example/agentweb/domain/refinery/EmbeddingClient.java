package com.example.agentweb.domain.refinery;

/**
 * 文本 → 向量 抽象. 实现由 infra 层提供 (Ark / 本地模型 / mock).
 *
 * <p>Domain 持有此接口仅用作 port; 不依赖具体 HTTP / SDK 细节.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public interface EmbeddingClient {

    /**
     * 把文本转成 dense 向量. 长度必须与 {@link #dimension()} 一致.
     *
     * @param text 待 embed 的文本, 实现可对超长输入做兜底截断
     * @return 浮点向量, 长度 = dimension()
     */
    float[] embed(String text);

    /**
     * 实现使用的模型名 (例: doubao-embedding-vision).
     * 入库时写入 chunk.embedding_model, 便于后续模型替换时识别老数据.
     *
     * @return 模型名
     */
    String modelName();

    /**
     * 向量维度. 启动时校验 + 入库前做长度断言.
     *
     * @return 维度
     */
    int dimension();
}
