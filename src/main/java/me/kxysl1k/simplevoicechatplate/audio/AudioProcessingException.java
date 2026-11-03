package me.kxysl1k.simplevoicechatplate.audio;

public class AudioProcessingException extends RuntimeException {

    public enum ErrorType {
        FORBIDDEN_403("chat.error_forbidden", "HTTP 403 Forbidden — YouTube заблокировал запрос"),
        AGE_RESTRICTED("chat.error_age_restricted", "Видео с возрастным ограничением (требуется cookies.txt)"),
        UNAVAILABLE_DELETED("chat.error_unavailable", "Видео удалено, скрыто или недоступно"),
        COPYRIGHT("chat.error_copyright", "Видео заблокировано правообладателем"),
        FFMPEG_ERROR("chat.error_ffmpeg", "Ошибка конвертации аудио через FFmpeg"),
        NETWORK_ERROR("chat.audio_error", "Сетевая ошибка при загрузке аудио"),
        UNKNOWN("chat.audio_error", "Неизвестная ошибка обработки аудио");

        private final String messageKey;
        private final String defaultDescription;

        ErrorType(String messageKey, String defaultDescription) {
            this.messageKey = messageKey;
            this.defaultDescription = defaultDescription;
        }

        public String getMessageKey() {
            return messageKey;
        }

        public String getDefaultDescription() {
            return defaultDescription;
        }
    }

    private final ErrorType errorType;
    private final String technicalDetails;

    public AudioProcessingException(ErrorType errorType, String technicalDetails) {
        super(errorType.getDefaultDescription() + (technicalDetails != null ? " (" + technicalDetails + ")" : ""));
        this.errorType = errorType;
        this.technicalDetails = technicalDetails;
    }

    public AudioProcessingException(ErrorType errorType, String technicalDetails, Throwable cause) {
        super(errorType.getDefaultDescription() + (technicalDetails != null ? " (" + technicalDetails + ")" : ""), cause);
        this.errorType = errorType;
        this.technicalDetails = technicalDetails;
    }

    public ErrorType getErrorType() {
        return errorType;
    }

    public String getMessageKey() {
        return errorType.getMessageKey();
    }

    public String getTechnicalDetails() {
        return technicalDetails;
    }

    public static ErrorType classifyFromOutput(String output) {
        if (output == null) return ErrorType.UNKNOWN;
        String lower = output.toLowerCase();

        if (lower.contains("403: forbidden") || lower.contains("http error 403") || lower.contains("forbidden")) {
            return ErrorType.FORBIDDEN_403;
        }
        if (lower.contains("confirm your age") || lower.contains("sign in to confirm")
                || lower.contains("inappropriate for some users") || lower.contains("age-restricted")) {
            return ErrorType.AGE_RESTRICTED;
        }
        if (lower.contains("removed for violating") || lower.contains("video has been removed")
                || lower.contains("private video") || lower.contains("video unavailable") || lower.contains("deleted")) {
            return ErrorType.UNAVAILABLE_DELETED;
        }
        if (lower.contains("copyright") || lower.contains("blocked on copyright")) {
            return ErrorType.COPYRIGHT;
        }
        return ErrorType.UNKNOWN;
    }
}
