package com.smartagri.ai;

/**
 * AI 调用异常：由上层熔断/降级统一兜底。
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
