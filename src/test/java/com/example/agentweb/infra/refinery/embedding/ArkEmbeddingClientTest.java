package com.example.agentweb.infra.refinery.embedding;

import com.example.agentweb.infra.refinery.config.RefineryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public class ArkEmbeddingClientTest {

    private static final String ENDPOINT = "https://ark.test.local/api/v3";
    private static final String API_KEY = "test-key-xyz";
    private static final String MODEL = "doubao-embedding-vision";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private RefineryProperties props;
    private ArkEmbeddingClient client;

    @BeforeEach
    public void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        props = new RefineryProperties();
        props.getEmbedding().setEndpoint(ENDPOINT);
        props.getEmbedding().setModel(MODEL);
        props.getEmbedding().setApiKey(API_KEY);
        props.getEmbedding().setDimension(3);
        props.getEmbedding().setMaxInputChars(10);
        client = new ArkEmbeddingClient(props, restTemplate);
    }

    @Test
    public void embed_should_build_post_body_and_parse_response_vector() {
        server.expect(requestTo(ENDPOINT + "/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.input").value("hello"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.1, 0.2, 0.3]}]}",
                        MediaType.APPLICATION_JSON));

        float[] vec = client.embed("hello");

        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, vec, 1e-6f);
        server.verify();
    }

    @Test
    public void embed_input_exceeding_max_input_chars_should_be_prefix_truncated() {
        server.expect(requestTo(ENDPOINT + "/embeddings"))
                .andExpect(jsonPath("$.input").value("abcdefghij"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.0, 0.0, 0.0]}]}",
                        MediaType.APPLICATION_JSON));

        client.embed("abcdefghijklmnopqrstuvwxyz");

        server.verify();
    }

    @Test
    public void embed_on_http_500_should_throw() {
        server.expect(requestTo(ENDPOINT + "/embeddings"))
                .andRespond(withServerError());

        assertThrows(RestClientException.class, () -> client.embed("hello"));
    }

    @Test
    public void embed_response_data_missing_embedding_should_throw() {
        server.expect(requestTo(ENDPOINT + "/embeddings"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> client.embed("hello"));
    }

    @Test
    public void embed_response_without_top_level_data_should_throw() {
        server.expect(requestTo(ENDPOINT + "/embeddings"))
                .andRespond(withSuccess("{\"error\":\"oops\"}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> client.embed("hello"));
    }

    @Test
    public void validate_dimension_when_length_mismatches_should_throw_illegal_state_exception() {
        props.getEmbedding().setDimension(2048);
        server.expect(requestTo(ENDPOINT + "/embeddings"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.1, 0.2, 0.3]}]}",
                        MediaType.APPLICATION_JSON));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.validateDimension());
        assertEquals(true, ex.getMessage().contains("2048"));
        assertEquals(true, ex.getMessage().contains("3"));
    }

    @Test
    public void validate_dimension_when_length_matches_should_return_normally() {
        server.expect(requestTo(ENDPOINT + "/embeddings"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.1, 0.2, 0.3]}]}",
                        MediaType.APPLICATION_JSON));

        client.validateDimension();

        server.verify();
    }

    @Test
    public void model_name_should_reflect_config() {
        assertEquals(MODEL, client.modelName());
    }

    @Test
    public void dimension_should_reflect_config() {
        assertEquals(3, client.dimension());
    }
}
