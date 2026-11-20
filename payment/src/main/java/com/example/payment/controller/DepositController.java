package com.example.payment.controller;

import com.example.payment.dto.CreateResult;
import com.example.payment.dto.PaymentResponse;
import com.example.payment.dto.RegisterDepositRequest;
import com.example.payment.service.DepositService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/deposits")
class DepositController {

    private final DepositService deposits;

    DepositController(DepositService deposits) {
        this.deposits = deposits;
    }

    @PostMapping
    ResponseEntity<PaymentResponse> register(@Valid @RequestBody RegisterDepositRequest req) {
        CreateResult result = deposits.register(req.vendorPaymentId());
        PaymentResponse body = PaymentResponse.from(result.payment());
        if (result.created()) {
            return ResponseEntity.created(URI.create("/deposits/" + body.id())).body(body);
        }
        return ResponseEntity.ok(body);
    }
}
