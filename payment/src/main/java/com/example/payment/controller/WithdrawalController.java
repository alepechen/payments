package com.example.payment.controller;

import com.example.payment.dto.CreateResult;
import com.example.payment.dto.CreateWithdrawalRequest;
import com.example.payment.dto.PaymentResponse;
import com.example.payment.service.WithdrawalService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/withdrawals")
class WithdrawalController {

    private final WithdrawalService withdrawals;

    WithdrawalController(WithdrawalService withdrawals) {
        this.withdrawals = withdrawals;
    }

    @PostMapping
    ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreateWithdrawalRequest req) {
        CreateResult result = withdrawals.create(req);
        PaymentResponse body = PaymentResponse.from(result.payment());
        if (result.created()) {
            return ResponseEntity.created(URI.create("/withdrawals/" + body.id())).body(body);
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.from(withdrawals.get(id));
    }
}
