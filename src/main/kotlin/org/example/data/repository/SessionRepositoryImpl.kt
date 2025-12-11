package org.example.data.repository

import org.example.domain.model.DialogSession
import org.example.domain.repository.SessionRepository
import org.slf4j.LoggerFactory

/**
 * Реализация репозитория для хранения одной сессии в памяти
 */
class SessionRepositoryImpl : SessionRepository {
    private val logger = LoggerFactory.getLogger(SessionRepositoryImpl::class.java)
    
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
            isComplete = false,
            accumulatedTotalTokens = 0
        )
        
        currentSession = session
        logger.info("Создана новая сессия диалога: модель=$model, maxRounds=$maxRounds, maxTokens=$maxTokens, disableSearch=$disableSearch")
        return session
    }
    
    override fun getSession(): DialogSession? {
        return currentSession
    }
    
    override fun clearSession() {
        logger.debug("Сессия диалога очищена")
        currentSession = null
    }
    
    override fun shutdown() {
        logger.debug("SessionRepository остановлен")
        currentSession = null
    }
}


