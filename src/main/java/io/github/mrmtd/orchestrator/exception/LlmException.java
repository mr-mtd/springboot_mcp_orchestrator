package io.github.mrmtd.orchestrator.exception;

/** The model call failed or returned nothing. Answered with 502. */
public class LlmException extends RuntimeException {

    public LlmException(Throwable cause) {
        super("The assistant could not answer right now", cause);
    }

    public LlmException() {
        super("The assistant could not answer right now");
    }
}
