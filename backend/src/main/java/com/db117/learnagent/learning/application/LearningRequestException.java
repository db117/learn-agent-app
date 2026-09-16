package com.db117.learnagent.learning.application;

/** Learning API 对外稳定的业务错误；不把 Domain 或数据库异常文本暴露给 UI。 */
public final class LearningRequestException extends RuntimeException {
    private final int status;
    private final String code;
    private final String publicMessage;

    private LearningRequestException(int status, String code, String publicMessage) {
        super(code);
        this.status = status;
        this.code = code;
        this.publicMessage = publicMessage;
    }

    public static LearningRequestException badRequest(String code, String message) {
        return new LearningRequestException(400, code, message);
    }

    public static LearningRequestException notFound(String code, String message) {
        return new LearningRequestException(404, code, message);
    }

    public static LearningRequestException conflict(String code, String message) {
        return new LearningRequestException(409, code, message);
    }

    public static LearningRequestException internal(String code, String message) {
        return new LearningRequestException(500, code, message);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String publicMessage() {
        return publicMessage;
    }
}
