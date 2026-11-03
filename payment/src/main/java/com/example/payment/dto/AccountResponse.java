package com.example.payment.dto;

import com.example.payment.domain.Account;

public record AccountResponse(long balance, long held, long available, String currency) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getBalance(), account.getHeld(), account.available(), account.getCurrency());
    }
}
