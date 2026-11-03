package com.example.payment.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiTests {

    @Autowired
    MockMvc mvc;

    static final String WITHDRAWAL = """
            {"idempotencyKey":"key-12345","amount":100,"currency":"USD"}""";

    @Test
    void createdWithdrawalCanBeFetched() throws Exception {
        String body = mvc.perform(post("/withdrawals").contentType(MediaType.APPLICATION_JSON)
                        .content(WITHDRAWAL.replace("key-12345", "key-" + UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.amount").value(100))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/withdrawals/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void replayedWithdrawalReturnsOkWithSameId() throws Exception {
        String key = "key-" + UUID.randomUUID();
        String body = WITHDRAWAL.replace("key-12345", key);
        String first = mvc.perform(post("/withdrawals").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(first, "$.id");

        mvc.perform(post("/withdrawals").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void sameKeyDifferentAmountIsConflict() throws Exception {
        String key = "key-" + UUID.randomUUID();
        mvc.perform(post("/withdrawals").contentType(MediaType.APPLICATION_JSON)
                        .content(WITHDRAWAL.replace("key-12345", key)))
                .andExpect(status().isCreated());

        mvc.perform(post("/withdrawals").contentType(MediaType.APPLICATION_JSON)
                        .content(WITHDRAWAL.replace("key-12345", key).replace("100", "200")))
                .andExpect(status().isConflict());
    }

    @Test
    void wrongCurrencyIsBadRequest() throws Exception {
        mvc.perform(post("/withdrawals").contentType(MediaType.APPLICATION_JSON)
                        .content(WITHDRAWAL.replace("USD", "EUR")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativeAmountIsBadRequest() throws Exception {
        mvc.perform(post("/withdrawals").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idempotencyKey":"key-12345","amount":-5,"currency":"USD"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void blankVendorPaymentIdIsBadRequest() throws Exception {
        mvc.perform(post("/deposits").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vendorPaymentId":""}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerDepositIsNotImplemented() throws Exception {
        mvc.perform(post("/deposits").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vendorPaymentId":"v-1"}"""))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void unknownWithdrawalIsNotFound() throws Exception {
        mvc.perform(get("/withdrawals/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void accountShowsAvailableAsBalanceMinusHeld() throws Exception {
        String body = mvc.perform(get("/accounts/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andReturn().getResponse().getContentAsString();
        // Independent of whatever other tests left on the shared in-memory account
        long balance = ((Number) JsonPath.read(body, "$.balance")).longValue();
        long held = ((Number) JsonPath.read(body, "$.held")).longValue();
        long available = ((Number) JsonPath.read(body, "$.available")).longValue();
        org.assertj.core.api.Assertions.assertThat(available).isEqualTo(balance - held);
    }

    @Test
    void insufficientFundsMapsToUnprocessableEntity() {
        var problem = new ApiExceptionHandler()
                .insufficientFunds(new com.example.payment.exception.InsufficientFundsException(1L, 200, 100));

        org.assertj.core.api.Assertions.assertThat(problem.getStatus()).isEqualTo(422);
        org.assertj.core.api.Assertions.assertThat(problem.getDetail()).contains("requested 200", "available 100");
    }

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
