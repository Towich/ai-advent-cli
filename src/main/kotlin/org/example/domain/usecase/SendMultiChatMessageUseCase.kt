package org.example.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.example.domain.model.ChatRequest
import org.example.domain.model.Message
import org.example.domain.model.TokenUsage
import org.example.domain.repository.GigaChatRepository
import org.example.domain.repository.PerplexityRepository
import org.example.infrastructure.config.Vendor
import org.example.infrastructure.config.VendorDetector

/**
 * Use Case для отправки сообщения в несколько моделей одновременно
 */
class SendMultiChatMessageUseCase(
    private val perplexityRepository: PerplexityRepository,
    private val gigaChatRepository: GigaChatRepository,
    private val defaultModel: String,
    private val defaultMaxTokens: Int
) {
    /**
     * Результат запроса к одной модели
     */
    data class ModelResult(
        val vendor: String,
        val model: String,
        val content: String?,
        val executionTimeMs: Long,
        val usage: TokenUsage?,
        val success: Boolean,
        val error: String? = null
    )

    suspend fun execute(
        message: String,
        models: List<Pair<String, String?>>, // List of (vendor, model)
        maxTokens: Int?,
        disableSearch: Boolean?,
        systemPrompt: String?,
        outputFormat: String?,
        outputSchema: String?,
        temperature: Double?
    ): List<ModelResult> = coroutineScope {
        // Валидация
        if (message.isBlank()) {
            throw IllegalArgumentException("Сообщение не может быть пустым")
        }

        if (models.isEmpty()) {
            throw IllegalArgumentException("Необходимо указать хотя бы одну модель")
        }

        // Построить сообщения для отправки
        val messages = buildMessages(message, systemPrompt, outputFormat, outputSchema)

        // Получить параметры
        val maxTokensValue = maxTokens ?: defaultMaxTokens
        val disableSearchValue = disableSearch ?: true

        // Отправить запросы параллельно ко всем моделям
        val results = models.map { (vendorStr, modelStr) ->
            async {
                val vendor = VendorDetector.parseVendor(vendorStr)
                    ?: throw IllegalArgumentException("Неизвестный vendor: $vendorStr")

                val model = modelStr ?: defaultModel

                val startTime = System.currentTimeMillis()

                try {
                    val result = when (vendor) {
                        Vendor.PERPLEXITY -> perplexityRepository.sendMessage(
                            messages,
                            model,
                            maxTokensValue,
                            disableSearchValue,
                            temperature
                        )
                        Vendor.GIGACHAT -> gigaChatRepository.sendMessage(
                            messages,
                            model,
                            maxTokensValue,
                            disableSearchValue,
                            temperature
                        )
                    }

                    val executionTimeMs = System.currentTimeMillis() - startTime

                    result.fold(
                        onSuccess = { (content, responseModel, usage) ->
                            ModelResult(
                                vendor = vendorStr,
                                model = responseModel,
                                content = content,
                                executionTimeMs = executionTimeMs,
                                usage = usage,
                                success = true
                            )
                        },
                        onFailure = { error ->
                            ModelResult(
                                vendor = vendorStr,
                                model = model,
                                content = null,
                                executionTimeMs = executionTimeMs,
                                usage = null,
                                success = false,
                                error = error.message ?: "Неизвестная ошибка"
                            )
                        }
                    )
                } catch (e: Exception) {
                    val executionTimeMs = System.currentTimeMillis() - startTime
                    ModelResult(
                        vendor = vendorStr,
                        model = model,
                        content = null,
                        executionTimeMs = executionTimeMs,
                        usage = null,
                        success = false,
                        error = e.message ?: "Неизвестная ошибка"
                    )
                }
            }
        }.awaitAll()

        results
    }

    private fun buildMessages(
        message: String,
        systemPrompt: String?,
        outputFormat: String?,
        outputSchema: String?
    ): List<Message> {
        val messages = mutableListOf<Message>()

        // Построить системный промпт
        val finalSystemPrompt = buildSystemPrompt(systemPrompt, outputFormat, outputSchema)
        finalSystemPrompt?.let { prompt ->
            messages.add(Message(role = Message.ROLE_SYSTEM, content = prompt))
        }

        // Добавить пользовательское сообщение
        messages.add(Message(role = Message.ROLE_USER, content = message))

        return messages
    }

    private fun buildSystemPrompt(
        systemPrompt: String?,
        outputFormat: String?,
        outputSchema: String?
    ): String? {
        val parts = mutableListOf<String>()

        systemPrompt?.let { parts.add(it) }

        outputFormat?.let { format ->
            parts.add(buildFormatSystemPrompt(format))

            if (format.lowercase() == "json" && outputSchema != null) {
                parts.add("Схема JSON: $outputSchema")
            }
        }

        return if (parts.isNotEmpty()) {
            parts.joinToString("\n\n")
        } else {
            null
        }
    }

    private fun buildFormatSystemPrompt(format: String): String {
        return """
            Ты — ассистент, который отвечает исключительно в формате $format без какой-либо дополнительной информации, пояснений или текста. 
            Каждый ответ должен быть валидным $format-объектом, содержащим только необходимую структурированную информацию по запросу. 
            Никакого свободного текста, описаний, комментариев или объяснений не добавляй.
            Также не добавляй служебных символов, по типу "```json"
            Не используй знаки переноса строк ('\n') и не переноси текст на д, пиши всё сплошным текстом
        """.trimIndent()
    }
}
