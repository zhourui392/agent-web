package com.example.agentweb;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "agent.fs.roots=/home"
})
@AutoConfigureMockMvc
public class FsControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    public void roots_should_return_configured() throws Exception {
        mvc.perform(get("/api/fs/roots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem("/home")));
    }
}
