package com.example.payment.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uq_payment_entry", columnNames = {"payment_id", "entry_type"}))
public class LedgerEntry {
    @Id @GeneratedValue Long id;
    @ManyToOne(optional = false) Account account;
    @ManyToOne(optional = false) @JoinColumn(name = "payment_id") Payment payment;
    @Enumerated(EnumType.STRING) @Column(name = "entry_type", nullable = false) LedgerEntryType entryType;
    @Column(nullable = false) long amount;
    @Column(nullable = false)
    Instant createdAt;

    public static LedgerEntry of(Account account, Payment payment, LedgerEntryType type, long amount) {
        LedgerEntry e = new LedgerEntry();
        e.account = account;
        e.payment = payment;
        e.entryType = type;
        e.amount = amount;
        e.createdAt = Instant.now();
        return e;
    }
}
