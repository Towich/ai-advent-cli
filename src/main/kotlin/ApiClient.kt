package org.example

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Message(
    val role: String,
    val content: String
)

@Serializable
data class ChatRequest(
    val model: String,
    val max_tokens: Int = 256,
    val disable_search: Boolean = true,
    val messages: List<Message>,
)

@Serializable
data class ChatResponse(
    val choices: List<Choice>
)

@Serializable
data class Choice(
    val message: Message
)

@Serializable
data class ErrorResponse(
    val error: ErrorDetail
)

@Serializable
data class ErrorDetail(
    val message: String,
    val type: String? = null
)

class ApiClient {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }
    
    suspend fun sendMessage(prompt: String): String {
        return try {
            val response: ChatResponse = client.post(Config.apiUrl) {
                header(HttpHeaders.Authorization, "Bearer ${Config.apiKey}")
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(
                    ChatRequest(
                        model = Config.model,
                        max_tokens = 256,
                        disable_search = true,
                        messages = listOf(
                            Message(role = "user", content = prompt)
                        )
                    )
                )
            }.body()
            
            response.choices.firstOrNull()?.message?.content
                ?: "Ошибка: пустой ответ от API"
        } catch (e: Exception) {
            when {
                e.message?.contains("401") == true -> 
                    "Ошибка: неверный API ключ. Проверьте переменную окружения PERPLEXITY_API_KEY"
                e.message?.contains("429") == true -> 
                    "Ошибка: превышен лимит запросов. Попробуйте позже"
                e.message?.contains("500") == true -> 
                    "Ошибка: проблема на стороне сервера API"
                else -> "Ошибка при запросе к API: ${e.message}"
            }
        }
    }
    
    fun close() {
        client.close()
    }
}

