package org.example

import org.example.dto.ChatApiRequest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

data class DialogSession(
    val sessionId: String,
    val systemPrompt: String?,
    val messages: MutableList<Message>,
    var currentRound: Int,
    val maxRounds: Int,
    val model: String,
    val maxTokens: Int,
    val disableSearch: Boolean,
    val createdAt: Long,
    var lastActivityAt: Long,
    val initialUserMessage: String,
    var isComplete: Boolean = false
) {
    fun addUserMessage(message: String) {
        messages.add(Message(role = "user", content = message))
    }
    
    fun addAssistantMessage(message: String) {
        messages.add(Message(role = "assistant", content = message))
    }
    
    fun incrementRound() {
        currentRound++
    }
    
    fun updateLastActivity() {
        lastActivityAt = System.currentTimeMillis()
    }
}

class SessionManager {
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
                cleanupExpiredSessions()
            }
        }
    }
    
    fun createSession(request: ChatApiRequest): DialogSession {
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val model = request.model ?: Config.model
        val maxTokens = request.maxTokens ?: 256
        val disableSearch = request.disableSearch ?: true
        val maxRounds = request.maxRounds ?: 1
        
        val messages = mutableListOf<Message>()
        
        // НЕ добавляем системное сообщение здесь - оно будет добавлено в buildMessagesHistory
        // с учетом контекста раунда. Также не добавляем первое пользовательское сообщение -
        // оно будет добавлено в buildMessagesHistory
        
        val session = DialogSession(
            sessionId = sessionId,
            systemPrompt = request.systemPrompt,
            messages = messages,
            currentRound = 0, // Начинаем с 0, после первого запроса станет 1
            maxRounds = maxRounds,
            model = model,
            maxTokens = maxTokens,
            disableSearch = disableSearch,
            createdAt = now,
            lastActivityAt = now,
            initialUserMessage = request.message,
            isComplete = false
        )
        
        sessions[sessionId] = session
        return session
    }
    
    fun getSession(sessionId: String): DialogSession? {
        return sessions[sessionId]
    }
    
    fun removeSession(sessionId: String) {
        sessions.remove(sessionId)
    }
    
    private fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        val expiredSessions = sessions.values.filter { session ->
            (now - session.lastActivityAt) > SESSION_TTL_MS
        }
        
        expiredSessions.forEach { session ->
            sessions.remove(session.sessionId)
        }
        
        if (expiredSessions.isNotEmpty()) {
            println("Очищено ${expiredSessions.size} неактивных сессий")
        }
    }
    
    fun shutdown() {
        cleanupJob.cancel()
    }
}