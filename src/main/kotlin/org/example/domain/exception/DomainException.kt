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
    
    class SessionNotFoundException(sessionId: String) : DomainException(
        "Сессия не найдена: $sessionId",
        "SESSION_NOT_FOUND"
    )
    
    class DialogCompletedException : DomainException(
        "Диалог завершен",
        "DIALOG_COMPLETED"
    )
    
    class MaxRoundsExceededException : DomainException(
        "Превышен лимит раундов",
        "MAX_ROUNDS_EXCEEDED"
    )
}


