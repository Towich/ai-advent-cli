package org.example.domain.exception

/**
 * Базовый класс для доменных исключений
 */
sealed class DomainException(message: String, val code: String) : Exception(message) {
    class EmptyMessageException : DomainException(
        "Поле 'message' не может быть пустым",
        "EMPTY_MESSAGE"
    )
    
    class InvalidMaxRoundsException : DomainException(
        "Поле 'maxRounds' должно быть >= 1",
        "INVALID_MAX_ROUNDS"
    )
    
    class DialogCompletedException : DomainException(
        "Диалог завершен",
        "DIALOG_COMPLETED"
    )
    
    class MaxRoundsExceededException : DomainException(
        "Превышен лимит раундов",
        "MAX_ROUNDS_EXCEEDED"
    )
    
    class BothCompressionThresholdsException : DomainException(
        "Нельзя указывать одновременно compressionMessagesThreshold и compressionTokensThreshold",
        "BOTH_COMPRESSION_THRESHOLDS"
    )
}


