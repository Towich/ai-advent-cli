package org.example.domain.repository

import org.example.domain.model.DialogSession

/**
 * Интерфейс репозитория для управления сессиями диалогов
 */
interface SessionRepository {
    /**
     * Создать новую сессию
     */
    fun createSession(
        sessionId: String,
        systemPrompt: String?,
        model: String,
        maxTokens: Int,
        disableSearch: Boolean,
        maxRounds: Int,
        initialUserMessage: String
    ): DialogSession
    
    /**
     * Получить сессию по ID
     */
    fun getSession(sessionId: String): DialogSession?
    
    /**
     * Удалить сессию
     */
    fun removeSession(sessionId: String)
    
    /**
     * Очистить истекшие сессии
     */
    fun cleanupExpiredSessions(ttlMs: Long)
    
    /**
     * Завершить работу репозитория
     */
    fun shutdown()
}


