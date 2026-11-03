package com.example.payment.domain;

import java.util.Map;
import java.util.Set;

public enum WithdrawalStatus {
    CREATED, HELD, INITIATED, COMPLETED, DECLINED;

    private static final Map<WithdrawalStatus, Set<WithdrawalStatus>> ALLOWED = Map.of(
            CREATED,   Set.of(HELD, DECLINED),
            HELD,      Set.of(INITIATED, DECLINED),
            INITIATED, Set.of(COMPLETED, DECLINED),
            COMPLETED, Set.of(),
            DECLINED,  Set.of()
    );

    public boolean canMoveTo(WithdrawalStatus next) { return ALLOWED.get(this).contains(next); }
    public boolean isTerminal() { return ALLOWED.get(this).isEmpty(); }
}
