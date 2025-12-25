package org.example.data.repository

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.data.remote.dto.LocalMessage
import org.example.data.remote.dto.LocalRequest
import org.example.data.remote.dto.LocalResponse
import org.example.domain.model.Message
import org.example.domain.model.TokenUsage
import org.example.domain.repository.LocalRepository
import org.slf4j.LoggerFactory

/**
 * Реализация репозитория для работы с Local API
 */
class LocalRepositoryImpl(
    private val apiUrl: String
) : LocalRepository {
    private val logger = LoggerFactory.getLogger(LocalRepositoryImpl::class.java)
    private val jsonSerializer = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonSerializer)
        }
        
        engine {
            requestTimeout = 300000 // 300 секунд
        }
    }
    
    override suspend fun sendMessage(
        messages: List<Message>,
        model: String,
        maxTokens: Int,
        disableSearch: Boolean,
        temperature: Double?
    ): Result<Triple<String, String, TokenUsage?>> {
        return try {
            // Конвертируем domain модели в DTO
            val localMessages = messages.map { message ->
                LocalMessage(role = message.role, content = message.content)
            }
            
            // Создаем объект запроса
            // Примечание: disableSearch игнорируется для Local API
            val request = LocalRequest(
                model = model,
                max_tokens = maxTokens,
                messages = localMessages,
                temperature = temperature
            )
            
            // Логируем запрос в нейросеть
            val totalMessages = messages.size
            val totalChars = messages.sumOf { it.content.length }
            val requestBodyJson = jsonSerializer.encodeToString(request)
            logger.info("Запрос в нейросеть [Local]: модель=$model, сообщений=$totalMessages, символов=$totalChars, maxTokens=$maxTokens${temperature?.let { ", temperature=$it" } ?: ""}")
            logger.debug("Body запроса [Local]:\n$requestBodyJson")
            
            val httpResponse = client.post(apiUrl) {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(request)
            }
            
            val statusCode = httpResponse.status.value
            if (statusCode !in 200..299) {
                val errorBody = try {
                    httpResponse.bodyAsText()
                } catch (e: Exception) {
                    "Не удалось прочитать тело ответа: ${e.message}"
                }
                val errorMessage = when (statusCode) {
                    400 -> "Неверный запрос к Local API (HTTP 400): $errorBody"
                    404 -> "Ресурс не найден в Local API (HTTP 404): $errorBody"
                    429 -> "Превышен лимит запросов. Попробуйте позже"
                    in 500..599 -> "Проблема на стороне сервера Local API (HTTP $statusCode): $errorBody"
                    else -> "Ошибка при запросе к Local API (HTTP $statusCode): $errorBody"
                }
                logger.error("Ошибка ответа от нейросети [Local]: HTTP $statusCode - $errorMessage")
                return Result.failure(Exception("HTTP $statusCode: $errorMessage"))
            }
            
            val response: LocalResponse = httpResponse.body()
            val responseBodyJson = jsonSerializer.encodeToString(response)
            val content = response.choices.firstOrNull()?.message?.content
            val responseModel = response.model ?: model
            val usage = response.usage?.let {
                TokenUsage(
                    promptTokens = it.prompt_tokens,
                    completionTokens = it.completion_tokens,
                    totalTokens = it.total_tokens
                )
            }
            
            // Логируем ответ от нейросети
            val contentLength = content?.length ?: 0
            val tokensInfo = usage?.let { "promptTokens=${it.promptTokens}, completionTokens=${it.completionTokens}, totalTokens=${it.totalTokens}" } ?: "N/A"
            logger.info("Ответ от нейросети [Local]: модель=$responseModel, длина=$contentLength, $tokensInfo")
            logger.debug("Body ответа [Local]:\n$responseBodyJson")
            
            if (content != null) {
                Result.success(Triple(content, responseModel, usage))
            } else {
                logger.error("Пустой ответ от нейросети [Local]")
                Result.failure(Exception("Пустой ответ от Local API"))
            }
        } catch (e: Exception) {
            val errorMessage = when {
                e.message?.contains("429") == true || e.message?.contains("HTTP 429") == true -> 
                    "Превышен лимит запросов. Попробуйте позже"
                e.message?.contains("400") == true || e.message?.contains("HTTP 400") == true ->
                    "Неверный запрос к Local API: ${e.message}"
                e.message?.contains("404") == true || e.message?.contains("HTTP 404") == true ->
                    "Ресурс не найден в Local API: ${e.message}"
                e.message?.contains("500") == true || e.message?.contains("HTTP 5") == true ->
                    "Проблема на стороне сервера Local API: ${e.message}"
                e.message?.contains("timeout") == true || e.message?.contains("Timeout") == true ->
                    "Таймаут при запросе к Local API. Возможно, запрос слишком большой или сервер перегружен"
                else -> "Ошибка при запросе к Local API: ${e.message ?: e.javaClass.simpleName}"
            }
            logger.error("Ошибка запроса в нейросеть [Local]: ${e.javaClass.simpleName} - $errorMessage", e)
            Result.failure(Exception(errorMessage))
        }
    }
    
    override fun close() {
        client.close()
    }
}

