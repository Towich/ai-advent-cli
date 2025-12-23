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
            logger.info("Генерация эмбеддинга для текста длиной ${text.length} символов, модель: $model")
            logger.debug("URL Ollama API: $apiUrl/api/embed")
            logger.debug("Текст запроса (первые 100 символов): ${text.take(100)}")
            
            val request = EmbedRequest(model = model, input = text)
            val url = "$apiUrl/api/embed"
            
            logger.debug("Отправка POST запроса к Ollama API: $url")
            val httpResponse = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            
            val statusCode = httpResponse.status.value
            logger.info("Получен ответ от Ollama API: HTTP $statusCode")
            
            // Читаем тело ответа как строку один раз для логирования и парсинга
            val responseBodyString = try {
                httpResponse.body<String>()
            } catch (e: Exception) {
                logger.error("Не удалось прочитать тело ответа: ${e.message}", e)
                return Result.failure(Exception("Не удалось прочитать тело ответа от Ollama API: ${e.message}"))
            }
            
            logger.debug("Тело ответа от Ollama API (первые 500 символов): ${responseBodyString.take(500)}")
            logger.debug("Полная длина тела ответа: ${responseBodyString.length} символов")
            
            if (statusCode !in 200..299) {
                val errorMessage = "Ошибка при запросе к Ollama API (HTTP $statusCode): $responseBodyString"
                logger.error("Ошибка HTTP от Ollama API: $errorMessage")
                logger.error("Полное тело ответа об ошибке: $responseBodyString")
                return Result.failure(Exception(errorMessage))
            }
            
            // Парсим ответ из строки
            val response: EmbedResponse = try {
                jsonSerializer.decodeFromString<EmbedResponse>(responseBodyString)
            } catch (e: Exception) {
                logger.error("Ошибка при парсинге ответа от Ollama API: ${e.message}", e)
                logger.error("Тело ответа, которое не удалось распарсить: $responseBodyString")
                return Result.failure(Exception("Ошибка при парсинге ответа от Ollama API: ${e.message}"))
            }
            
            logger.debug("Ответ распарсен успешно. embedding: ${response.embedding != null}, embeddings: ${response.embeddings != null}")
            
            // Используем поле embeddings (множественное число) - это основной формат ответа от Ollama
            val embedding: List<Double>? = if (response.embeddings != null && response.embeddings.isNotEmpty()) {
                logger.debug("Используется поле 'embeddings', количество: ${response.embeddings.size}")
                // Берем первый эмбеддинг из массива
                response.embeddings.firstOrNull()
            } else if (response.embedding != null) {
                logger.debug("Используется поле 'embedding' (для обратной совместимости)")
                response.embedding
            } else {
                null
            }
            
            if (embedding == null) {
                logger.error("Пустой ответ от Ollama API: поля 'embeddings' и 'embedding' отсутствуют или null")
                logger.error("Поле 'embeddings': ${response.embeddings}")
                logger.error("Поле 'embedding': ${response.embedding}")
                logger.error("Полное тело ответа: $responseBodyString")
                return Result.failure(Exception("Пустой ответ от Ollama API: поля 'embeddings' и 'embedding' отсутствуют"))
            }
            
            if (embedding.isEmpty()) {
                logger.error("Эмбеддинг пустой (размер: 0)")
                return Result.failure(Exception("Пустой эмбеддинг от Ollama API (размер: 0)"))
            }
            
            val floatEmbedding = embedding.map { it.toFloat() }
            logger.info("Эмбеддинг сгенерирован успешно, размерность: ${floatEmbedding.size}")
            logger.debug("Первые 5 значений эмбеддинга: ${floatEmbedding.take(5)}")
            
            Result.success(floatEmbedding)
        } catch (e: Exception) {
            logger.error("Исключение при генерации эмбеддинга: ${e.message}", e)
            logger.error("Тип исключения: ${e.javaClass.simpleName}")
            e.printStackTrace()
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

