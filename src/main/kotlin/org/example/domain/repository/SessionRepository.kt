package org.example.domain.repository

import org.example.domain.model.DialogSession

/**
 * Интерфейс репозитория для управления одной сессией диалога
 */
interface SessionRepository {
    /**
     * Создать или сбросить сессию
     */
    fun createOrResetSession(
        systemPrompt: String?,
        model: String,
        maxTokens: Int,
        disableSearch: Boolean,
        maxRounds: Int,
        initialUserMessage: String
    ): DialogSession
    
    /**
     * Получить текущую сессию
     */
    fun getSession(): DialogSession?
    
    /**
     * Очистить текущую сессию
     */
    fun clearSession()
    
    /**
     * Обновить текущую сессию (для сохранения изменений)
     */
    fun updateSession(session: DialogSession)
    
    /**
     * Завершить работу репозитория
     */
    fun shutdown()
}


