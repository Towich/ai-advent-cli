package org.example.presentation.validation

import org.example.domain.exception.DomainException
import org.example.presentation.dto.ChatApiRequest

/**
 * Валидатор для входящих запросов
 */
object RequestValidator {
    fun validate(request: ChatApiRequest): Result<Unit> {
        if (request.message.isBlank()) {
            return Result.failure(DomainException.EmptyMessageException())
        }
        
        if (request.maxRounds != null && request.maxRounds < 1) {
            return Result.failure(DomainException.InvalidMaxRoundsException())
        }
        
        return Result.success(Unit)
    }
}


