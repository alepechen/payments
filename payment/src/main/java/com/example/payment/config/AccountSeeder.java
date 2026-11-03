package com.example.payment.config;

import com.example.payment.domain.Account;
import com.example.payment.repository.AccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates the single account (demo/USD) on first start. */
@Component
class AccountSeeder implements ApplicationRunner {

    private final AccountRepository accounts;
    private final String owner;

    AccountSeeder(AccountRepository accounts, @Value("${app.user.name:demo}") String owner) {
        this.accounts = accounts;
        this.owner = owner;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accounts.findByOwner(owner).isEmpty()) {
            accounts.save(Account.open(owner, "USD"));
        }
    }
}
