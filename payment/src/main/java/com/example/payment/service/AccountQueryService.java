package com.example.payment.service;

import com.example.payment.domain.Account;
import com.example.payment.exception.NotFoundException;
import com.example.payment.repository.AccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the single account. Money movements live in {@link AccountService}. */
@Service
public class AccountQueryService {

    private final AccountRepository accounts;
    private final String owner;

    public AccountQueryService(AccountRepository accounts, @Value("${app.user.name:demo}") String owner) {
        this.accounts = accounts;
        this.owner = owner;
    }

    @Transactional(readOnly = true)
    public Account current() {
        return accounts.findByOwner(owner)
                .orElseThrow(() -> new NotFoundException("Account for %s not found".formatted(owner)));
    }
}
