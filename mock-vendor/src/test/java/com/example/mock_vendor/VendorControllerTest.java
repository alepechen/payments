package com.example.mock_vendor;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class VendorControllerTest {

    @Autowired MockMvc mvc;

    @AfterEach
    void resetChaos() throws Exception {
        mvc.perform(post("/admin/chaos/OK"));
    }

    private String createPayout(UUID reference) throws Exception {
        String body = """
                {"reference":"%s","amount":100,"currency":"USD"}""".formatted(reference);
        String response = mvc.perform(post("/payment").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CREATED")))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    @Test
    void createIsIdempotentByReference() throws Exception {
        UUID ref = UUID.randomUUID();
        String first = createPayout(ref);
        String second = createPayout(ref);
        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);
    }

    @Test
    void confirmCompletesAndIsRepeatable() throws Exception {
        String id = createPayout(UUID.randomUUID());
        mvc.perform(post("/payment/{id}/confirm", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")));
        mvc.perform(post("/payment/{id}/confirm", id))
                .andExpect(jsonPath("$.status", is("COMPLETED")));
        mvc.perform(get("/payment/{id}", id))
                .andExpect(jsonPath("$.status", is("COMPLETED")));
    }

    @Test
    void rejectModeRejectsOnConfirm() throws Exception {
        String id = createPayout(UUID.randomUUID());
        mvc.perform(post("/admin/chaos/REJECT")).andExpect(status().isOk());
        mvc.perform(post("/payment/{id}/confirm", id))
                .andExpect(jsonPath("$.status", is("REJECTED")));
        // terminal: switching back does not change it
        mvc.perform(post("/admin/chaos/OK"));
        mvc.perform(post("/payment/{id}/confirm", id))
                .andExpect(jsonPath("$.status", is("REJECTED")));
    }

    @Test
    void downModeAnswers503() throws Exception {
        String id = createPayout(UUID.randomUUID());
        mvc.perform(post("/admin/chaos/DOWN")).andExpect(status().isOk());
        mvc.perform(get("/payment/{id}", id)).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/payment/{id}/confirm", id)).andExpect(status().isServiceUnavailable());
    }

    @Test
    void unknownIdIs404() throws Exception {
        mvc.perform(get("/payment/{id}", "nope")).andExpect(status().isNotFound());
        mvc.perform(post("/payment/{id}/confirm", "nope")).andExpect(status().isNotFound());
    }

    @Test
    void adminDepositIsCompletedPayin() throws Exception {
        String response = mvc.perform(post("/admin/deposits").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1000,"currency":"USD"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type", is("PAYIN")))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.<String>read(response, "$.id");
        mvc.perform(get("/payment/{id}", id)).andExpect(jsonPath("$.amount", is(1000)));
    }
}
