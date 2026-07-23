package com.example.agentweb.infra.refinery.embedding;

import com.example.agentweb.domain.refinery.EmbeddingClient;
import com.example.agentweb.config.refinery.RefineryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Volcengine Ark embedding 客户端, OpenAI-兼容协议. 走 POST {endpoint}/embeddings, Bearer auth.
 *
 * <p>启动时 {@link #validateDimension()} 通过 {@code @PostConstruct} 触发一次,
 * 校验返回向量长度与配置 {@code dimension} 一致, 不一致直接 fail-fast.</p>
 *
 * <p>仅在 {@code agent.refinery.enabled=true} 时注册. 关闭态下整个 refinery 链路不参与启动校验.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
@Component
@ConditionalOnProperty(prefix = "agent.refinery", name = "enabled", havingValue = "true")
@Slf4j
public class ArkEmbeddingClient implements EmbeddingClient {

    private static final String EMBED_PATH = "/embeddings";
    private static final String PING_INPUT = "ping";

    private final RefineryProperties props;
    private final RestTemplate restTemplate;

    public ArkEmbeddingClient(RefineryProperties props,
                              @Qualifier("chatRagRestTemplate") RestTemplate restTemplate) {
        this.props = props;
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    public void init() {
        validateDimension();
        log.info("refinery-embedding-ready model={} dim={}", modelName(), dimension());
    }

    @Override
    public float[] embed(String text) {
        String payload = truncate(text);
        Map<String, Object> body = new HashMap<>(2);
        body.put("model", props.getEmbedding().getModel());
        body.put("input", payload);

        String url = props.getEmbedding().getEndpoint() + EMBED_PATH;
        Map<?, ?> resp = restTemplate.postForObject(url, new HttpEntity<>(body, authHeaders()), Map.class);
        return parseEmbedding(resp);
    }

    @Override
    public String modelName() {
        return props.getEmbedding().getModel();
    }

    @Override
    public int dimension() {
        return props.getEmbedding().getDimension();
    }

    /**
     * 跑一次 embed("ping"), 校验返回向量长度匹配配置 dimension.
     * 不一致抛 IllegalStateException, 启动失败.
     */
    public void validateDimension() {
        float[] vec = embed(PING_INPUT);
        if (vec.length != dimension()) {
            throw new IllegalStateException(
                    "embedding dimension mismatch: configured=" + dimension()
                            + " but server returned=" + vec.length
                            + " (model=" + modelName() + ")");
        }
    }

    private String truncate(String text) {
        int max = props.getEmbedding().getMaxInputChars();
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        log.warn("refinery-embed-truncated original={} max={}", text.length(), max);
        return text.substring(0, max);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + props.getEmbedding().getApiKey());
        return headers;
    }

    @SuppressWarnings("unchecked")
    private float[] parseEmbedding(Map<?, ?> resp) {
        if (resp == null || !(resp.get("data") instanceof List)) {
            throw new IllegalStateException("ark response missing data field: " + resp);
        }
        List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
        if (data.isEmpty()) {
            throw new IllegalStateException("ark response data array is empty");
        }
        Object embedding = data.get(0).get("embedding");
        if (!(embedding instanceof List)) {
            throw new IllegalStateException("ark response data[0].embedding missing or not array");
        }
        List<Number> raw = (List<Number>) embedding;
        float[] vec = new float[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            vec[i] = raw.get(i).floatValue();
        }
        return vec;
    }
}
