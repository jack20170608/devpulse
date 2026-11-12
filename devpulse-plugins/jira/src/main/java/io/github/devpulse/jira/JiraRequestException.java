package io.github.devpulse.jira;

import java.io.IOException;

public final class JiraRequestException extends IOException {
    private final String code;
    private final Integer httpStatus;
    private final boolean outcomeUnknown;
    private final boolean retryable;

    public JiraRequestException(String message, String code, Integer httpStatus,
            boolean outcomeUnknown, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.outcomeUnknown = outcomeUnknown;
        this.retryable = retryable;
    }

    public String code() { return code; }
    public Integer httpStatus() { return httpStatus; }
    public boolean outcomeUnknown() { return outcomeUnknown; }
    public boolean retryable() { return retryable; }
}
