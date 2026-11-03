package com.example.payment.service;

import org.hibernate.exception.ConstraintViolationException;
import java.util.Locale;

final class Constraints {

    static final String IDEMPOTENCY_KEY = "uq_client_idempotency_key";

    private Constraints() {}

    /** True if any cause in the chain is a constraint violation naming the given constraint. */
    static boolean isViolationOf(Throwable e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && cve.getConstraintName() != null
                    && cve.getConstraintName().toLowerCase(Locale.ROOT).contains(constraint)) {
                return true;
            }
        }
        return false;
    }
}
