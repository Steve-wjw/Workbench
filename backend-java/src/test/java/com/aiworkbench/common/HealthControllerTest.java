package com.aiworkbench.common;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class HealthControllerTest {
    @Test
    void liveProbeDoesNotRequireADatabaseOrPythonConnection() throws Exception {
        // Null JDBC and an unreachable Python address must not affect Java API liveness.
        var controller = new HealthController(null, RestClient.builder(), "http://127.0.0.1:1");
        standaloneSetup(controller).build()
                .perform(get("/api/v1/health/live"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.service").value("java-api"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").isString());
    }
}
