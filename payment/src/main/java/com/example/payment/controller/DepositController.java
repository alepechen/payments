package com.example.payment.controller;

import com.example.payment.dto.PaymentResponse;
import com.example.payment.dto.RegisterDepositRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/deposits")
class DepositController {

    @PostMapping
    ResponseEntity<PaymentResponse> register(@Valid @RequestBody RegisterDepositRequest req) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }
}
