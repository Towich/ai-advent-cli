package org.example.domain.model

import kotlinx.serialization.Serializable

/**
 * Domain модель сессии диалога
 */
@Serializable
data class DialogSession(
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
    var isComplete: Boolean = false,
    var accumulatedTotalTokens: Int = 0
) {
    fun addUserMessage(message: String) {
        messages.add(Message(role = Message.ROLE_USER, content = message))
    }
    
    fun addAssistantMessage(message: String) {
        messages.add(Message(role = Message.ROLE_ASSISTANT, content = message))
    }
    
    fun incrementRound() {
        currentRound++
    }
    
    fun updateLastActivity() {
        lastActivityAt = System.currentTimeMillis()
    }
    
    fun isExpired(ttlMs: Long): Boolean {
        return (System.currentTimeMillis() - lastActivityAt) > ttlMs
    }
}


