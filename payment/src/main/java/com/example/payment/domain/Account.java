package com.example.payment.domain;

import com.example.payment.exception.InsufficientFundsException;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;

@Entity
@Check(constraints = "balance >= 0 AND held >= 0 AND held <= balance")
public class Account {
    @Id
    @GeneratedValue
    Long id;
    @Column(nullable = false, unique = true) String owner;
    @Column(nullable = false, length = 3) String currency;
    @Column(nullable = false) long balance;
    @Column(nullable = false) long held;
    @Version
    long version;

    public static Account open(String owner, String currency) {
        Account a = new Account();
        a.owner = owner;
        a.currency = currency;
        return a;
    }

    public long getBalance() {
        return balance;
    }

    public long getHeld() {
        return held;
    }

    public Long getId() {
        return id;
    }

    public String getCurrency(){
        return currency;
    }

    public long available() { return balance - held; }

    public void credit(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
        this.balance += amount;
    }

    public void hold(long amount) {
        if (available() < amount) {
            throw new InsufficientFundsException(id, amount, available());
        }
        this.held += amount;
    }

    public void settle(long amount) {
        if (held < amount) {
            throw new IllegalStateException("Account %d: cannot settle %d, held %d".formatted(id, amount, held));
        }
        this.balance -= amount;
        this.held -= amount;
    }

    public void release(long amount) {
        if (held < amount) {
            throw new IllegalStateException("Account %d: cannot release %d, held %d".formatted(id, amount, held));
        }
        this.held -= amount;
    }
}
