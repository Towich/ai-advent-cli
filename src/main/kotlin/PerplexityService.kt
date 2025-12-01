package org.example

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.dto.ChatApiRequest

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
    val choices: List<Choice>,
    val model: String? = null
)

@Serializable
data class Choice(
    val message: Message
)

class PerplexityService {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }
    
    suspend fun sendMessage(request: ChatApiRequest): Result<Pair<String, String>> {
        return try {
            val model = request.model ?: Config.model
            val maxTokens = request.maxTokens ?: 256
            val disableSearch = request.disableSearch ?: true
            
            val response: ChatResponse = client.post(Config.apiUrl) {
                header(HttpHeaders.Authorization, "Bearer ${Config.apiKey}")
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(
                    ChatRequest(
                        model = model,
                        max_tokens = maxTokens,
                        disable_search = disableSearch,
                        messages = listOf(
                            Message(role = "user", content = request.message)
                        )
                    )
                )
            }.body()
            
            val content = response.choices.firstOrNull()?.message?.content
            if (content != null) {
                Result.success(Pair(content, response.model ?: model))
            } else {
                Result.failure(Exception("Пустой ответ от Perplexity API"))
            }
        } catch (e: Exception) {
            val errorMessage = when {
                e.message?.contains("401") == true -> 
                    "Неверный API ключ. Проверьте переменную окружения PERPLEXITY_API_KEY"
                e.message?.contains("429") == true -> 
                    "Превышен лимит запросов. Попробуйте позже"
                e.message?.contains("500") == true -> 
                    "Проблема на стороне сервера Perplexity API"
                else -> "Ошибка при запросе к Perplexity API: ${e.message}"
            }
            Result.failure(Exception(errorMessage))
        }
    }
    
    fun close() {
        client.close()
    }
}

