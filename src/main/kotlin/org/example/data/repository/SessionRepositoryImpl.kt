package org.example.data.repository

import kotlinx.coroutines.*
import org.example.domain.model.DialogSession
import org.example.domain.repository.SessionRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Реализация репозитория сессий с хранением в памяти
 */
class SessionRepositoryImpl : SessionRepository {
    private val sessions = ConcurrentHashMap<String, DialogSession>()
    private val cleanupJob: Job
    
    companion object {
        private const val SESSION_TTL_MS = 60 * 60 * 1000L // 1 час
        private const val CLEANUP_INTERVAL_MS = 5 * 60 * 1000L // 5 минут
    }
    
    init {
        // Запускаем фоновую задачу для очистки неактивных сессий
        cleanupJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                cleanupExpiredSessions(SESSION_TTL_MS)
            }
        }
    }
    
    override fun createSession(
        sessionId: String,
        systemPrompt: String?,
        model: String,
        maxTokens: Int,
        disableSearch: Boolean,
        maxRounds: Int,
        initialUserMessage: String
    ): DialogSession {
        val now = System.currentTimeMillis()
        
        val session = DialogSession(
            sessionId = sessionId,
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
        
        sessions[sessionId] = session
        return session
    }
    
    override fun getSession(sessionId: String): DialogSession? {
        return sessions[sessionId]
    }
    
    override fun removeSession(sessionId: String) {
        sessions.remove(sessionId)
    }
    
    override fun cleanupExpiredSessions(ttlMs: Long) {
        val expiredSessions = sessions.values.filter { session ->
            session.isExpired(ttlMs)
        }
        
        expiredSessions.forEach { session ->
            sessions.remove(session.sessionId)
        }
        
        if (expiredSessions.isNotEmpty()) {
            println("Очищено ${expiredSessions.size} неактивных сессий")
        }
    }
    
    override fun shutdown() {
        cleanupJob.cancel()
    }
}


