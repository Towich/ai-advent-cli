package org.example.data.repository

import org.example.domain.model.DialogSession
import org.example.domain.repository.SessionRepository

/**
 * Реализация репозитория для хранения одной сессии в памяти
 */
class SessionRepositoryImpl : SessionRepository {
    @Volatile
    private var currentSession: DialogSession? = null
    
    override fun createOrResetSession(
        systemPrompt: String?,
        model: String,
        maxTokens: Int,
        disableSearch: Boolean,
        maxRounds: Int,
        initialUserMessage: String
    ): DialogSession {
        val now = System.currentTimeMillis()
        
        val session = DialogSession(
            systemPrompt = systemPrompt,
            messages = mutableListOf(),
            currentRound = 0,
            maxRounds = maxRounds,
            model = model,
            maxTokens = maxTokens,
            disableSearch = disableSearch,
            createdAt = now,
            lastActivityAt = now,
            initialUserMessage = initialUserMessage,
            isComplete = false
        )
        
        currentSession = session
        return session
    }
    
    override fun getSession(): DialogSession? {
        return currentSession
    }
    
    override fun clearSession() {
        currentSession = null
    }
    
    override fun shutdown() {
        currentSession = null
    }
}


