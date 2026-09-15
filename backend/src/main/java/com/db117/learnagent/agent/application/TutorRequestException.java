package com.db117.learnagent.agent.application;

/** Tutor API 的安全业务错误；对外只暴露稳定错误码和短消息。 */
public final class TutorRequestException extends RuntimeException {
    private final int status;
    private final String code;
    private final String publicMessage;

    private TutorRequestException(int status, String code, String publicMessage) {
        super(code);
        this.status = status;
        this.code = code;
        this.publicMessage = publicMessage;
    }

    public static TutorRequestException badRequest(String code, String message) {
        return new TutorRequestException(400, code, message);
    }

    public static TutorRequestException notFound(String code, String message) {
        return new TutorRequestException(404, code, message);
    }

    public static TutorRequestException conflict(String code, String message) {
        return new TutorRequestException(409, code, message);
    }

    public static TutorRequestException serviceUnavailable(String code, String message) {
        return new TutorRequestException(503, code, message);
    }

    public static TutorRequestException internal(String code, String message) {
        return new TutorRequestException(500, code, message);
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
