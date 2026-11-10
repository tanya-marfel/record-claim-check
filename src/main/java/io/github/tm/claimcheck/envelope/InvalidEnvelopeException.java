package io.github.tm.claimcheck.envelope;

import java.io.Serial;

public class InvalidEnvelopeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = -2913334826699823315L;

    public InvalidEnvelopeException(String message) {
        super(message);
    }
    public InvalidEnvelopeException(String message, Throwable cause) {
        super(message, cause);
    }
}
