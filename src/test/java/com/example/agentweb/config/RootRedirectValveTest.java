package com.example.agentweb.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author zhourui(V33215020)
 */
class RootRedirectValveTest {

    @Test
    void normalize_should_append_single_trailing_slash() {
        assertThat(RootRedirectValve.normalize("/qa")).isEqualTo("/qa/");
    }

    @Test
    void normalize_should_collapse_existing_trailing_slashes() {
        assertThat(RootRedirectValve.normalize("/qa/")).isEqualTo("/qa/");
        assertThat(RootRedirectValve.normalize("/qa///")).isEqualTo("/qa/");
    }

    @Test
    void normalize_should_prepend_missing_leading_slash() {
        assertThat(RootRedirectValve.normalize("qa")).isEqualTo("/qa/");
    }

    @Test
    void normalize_should_degrade_to_root_when_empty() {
        assertThat(RootRedirectValve.normalize(null)).isEqualTo("/");
        assertThat(RootRedirectValve.normalize("")).isEqualTo("/");
        assertThat(RootRedirectValve.normalize("/")).isEqualTo("/");
    }
}
