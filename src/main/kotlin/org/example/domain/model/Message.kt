package org.example.domain.model

import kotlinx.serialization.Serializable

/**
 * Domain модель сообщения в диалоге
 */
@Serializable
data class Message(
    val role: String,
    val content: String
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}


