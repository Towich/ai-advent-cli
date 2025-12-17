package org.example.application

import org.example.domain.usecase.SendChatMessageWithToolsUseCase

/**
 * Переиспользуемый сервис для выполнения "chat with tools" как из HTTP, так и из фоновых задач.
 * Содержит общую валидацию и единый вызов UseCase.
 */
class ChatWithToolsService(
    private val useCase: SendChatMessageWithToolsUseCase
) {
    data class Command(
        val message: String,
        val vendor: String,
        val model: String? = null,
        val maxTokens: Int? = null,
        val disableSearch: Boolean? = null,
        val systemPrompt: String? = null,
        val outputFormat: String? = null,
        val outputSchema: String? = null,
        val temperature: Double? = null,
        val mcpServerUrl: String,
        val maxToolIterations: Int? = 10
    )

    suspend fun execute(command: Command): Result<SendChatMessageWithToolsUseCase.ChatWithToolsResult> {
        validate(command)?.let { return Result.failure(IllegalArgumentException(it)) }

        val maxToolIterations = command.maxToolIterations ?: 10
        return useCase.execute(
            message = command.message,
            vendor = command.vendor,
            model = command.model,
            maxTokens = command.maxTokens,
            disableSearch = command.disableSearch,
            systemPrompt = command.systemPrompt,
            outputFormat = command.outputFormat,
            outputSchema = command.outputSchema,
            temperature = command.temperature,
            mcpServerUrl = command.mcpServerUrl,
            maxToolIterations = maxToolIterations
        )
    }

    private fun validate(command: Command): String? {
        if (command.message.isBlank()) return "Сообщение не может быть пустым"
        if (command.mcpServerUrl.isBlank()) return "URL MCP сервера не может быть пустым"
        val maxToolIterations = command.maxToolIterations ?: 10
        if (maxToolIterations < 1 || maxToolIterations > 50) return "maxToolIterations должен быть от 1 до 50"
        if (command.vendor.isBlank()) return "vendor не может быть пустым"
        return null
    }
}


