package com.example.mock_vendor;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Failure mode of the fake vendor, switched through the admin API.
 */
@Component
class VendorChaos {

    /** OK: normal. DOWN: payment endpoints answer 503. REJECT: confirm ends in REJECTED. LOST_RESPONSE: effect applied, 503 returned. */
    enum Mode { OK, DOWN, REJECT, LOST_RESPONSE }

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.OK);

    Mode get() {
        return mode.get();
    }

    void set(Mode newMode) {
        mode.set(newMode);
    }

    void failIfDown() {
        if (mode.get() == Mode.DOWN) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "vendor is down");
        }
    }

    void failIfLostResponse() {
        if (mode.get() == Mode.LOST_RESPONSE) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "applied, but response lost");
        }
    }
}
