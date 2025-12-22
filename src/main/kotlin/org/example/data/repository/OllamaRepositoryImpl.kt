package org.example.data.repository

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.domain.repository.OllamaRepository
import org.slf4j.LoggerFactory

/**
 * Реализация репозитория для работы с Ollama API
 */
class OllamaRepositoryImpl(
    private val apiUrl: String
) : OllamaRepository {
    private val logger = LoggerFactory.getLogger(OllamaRepositoryImpl::class.java)
    private val jsonSerializer = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonSerializer)
        }
        
        engine {
            requestTimeout = 120000 // 120 секунд для больших батчей
        }
    }

    @Serializable
    private data class EmbedRequest(
        val model: String,
        val input: String
    )

    @Serializable
    private data class EmbedBatchRequest(
        val model: String,
        val input: List<String>
    )

    @Serializable
    private data class EmbedResponse(
        val embedding: List<Double>? = null,
        val embeddings: List<List<Double>>? = null
    )

    override suspend fun generateEmbedding(text: String, model: String): Result<List<Float>> {
        return try {
            logger.debug("Генерация эмбеддинга для текста длиной ${text.length} символов, модель: $model")
            
            val request = EmbedRequest(model = model, input = text)
            val url = "$apiUrl/api/embed"
            
            val httpResponse = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            
            val statusCode = httpResponse.status.value
            if (statusCode !in 200..299) {
                val errorBody = try {
                    httpResponse.body<String>()
                } catch (e: Exception) {
                    "Не удалось прочитать тело ответа: ${e.message}"
                }
                val errorMessage = "Ошибка при запросе к Ollama API (HTTP $statusCode): $errorBody"
                logger.error(errorMessage)
                return Result.failure(Exception(errorMessage))
            }
            
            val response: EmbedResponse = httpResponse.body()
            val embedding = response.embedding
                ?: return Result.failure(Exception("Пустой ответ от Ollama API"))
            
            val floatEmbedding = embedding.map { it.toFloat() }
            logger.debug("Эмбеддинг сгенерирован, размерность: ${floatEmbedding.size}")
            
            Result.success(floatEmbedding)
        } catch (e: Exception) {
            logger.error("Ошибка при генерации эмбеддинга: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun generateEmbeddings(texts: List<String>, model: String): Result<List<List<Float>>> {
        return try {
            logger.info("Генерация эмбеддингов для ${texts.size} текстов, модель: $model")
            
            val request = EmbedBatchRequest(model = model, input = texts)
            val url = "$apiUrl/api/embed"
            
            val httpResponse = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            
            val statusCode = httpResponse.status.value
            if (statusCode !in 200..299) {
                val errorBody = try {
                    httpResponse.body<String>()
                } catch (e: Exception) {
                    "Не удалось прочитать тело ответа: ${e.message}"
                }
                val errorMessage = "Ошибка при запросе к Ollama API (HTTP $statusCode): $errorBody"
                logger.error(errorMessage)
                return Result.failure(Exception(errorMessage))
            }
            
            val response: EmbedResponse = httpResponse.body()
            val embeddings = response.embeddings
                ?: return Result.failure(Exception("Пустой ответ от Ollama API"))
            
            val floatEmbeddings = embeddings.map { embedding ->
                embedding.map { it.toFloat() }
            }
            
            logger.info("Эмбеддинги сгенерированы: ${floatEmbeddings.size} векторов, размерность: ${floatEmbeddings.firstOrNull()?.size ?: 0}")
            
            Result.success(floatEmbeddings)
        } catch (e: Exception) {
            logger.error("Ошибка при генерации эмбеддингов: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun close() {
        client.close()
    }
}

