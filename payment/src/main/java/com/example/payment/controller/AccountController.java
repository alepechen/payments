package com.example.payment.controller;

import com.example.payment.dto.AccountResponse;
import com.example.payment.service.AccountQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
class AccountController {

    private final AccountQueryService accounts;

    AccountController(AccountQueryService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/me")
    AccountResponse me() {
        return AccountResponse.from(accounts.current());
    }
}
