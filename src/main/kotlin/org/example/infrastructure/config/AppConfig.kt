package org.example.infrastructure.config

/**
 * Конфигурация приложения
 */
object AppConfig {
    private const val DEFAULT_API_URL = "https://api.perplexity.ai/chat/completions"
    private const val DEFAULT_MODEL = "sonar"
    private const val DEFAULT_GIGACHAT_API_URL = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions"
    private const val DEFAULT_HUGGINGFACE_API_URL = "https://router.huggingface.co/v1/chat/completions"
    private const val DEFAULT_SERVER_PORT = 8080
    private const val DEFAULT_MAX_TOKENS = 256
    private const val DEFAULT_MCP_SERVER_URL = "http://localhost:8002/mcp"
    private const val DEFAULT_REMINDER_SUMMARY_INTERVAL_MS = 60_000L
    private const val DEFAULT_REMINDER_SUMMARY_ENABLED = false

    val perplexityApiKey: String
        get() = System.getenv("PERPLEXITY_API_KEY") 
            ?: throw IllegalStateException(
                "PERPLEXITY_API_KEY не установлен. " +
                "Установите переменную окружения: export PERPLEXITY_API_KEY=your_api_key"
            )
    
    val perplexityApiUrl: String
        get() = System.getenv("PERPLEXITY_API_URL") ?: DEFAULT_API_URL
    
    val model: String
        get() = System.getenv("PERPLEXITY_MODEL") ?: DEFAULT_MODEL
    
    /**
     * Authorization key для получения Access token через OAuth
     * Используется для Basic аутентификации при запросе токена
     */
    val gigachatApiKey: String
        get() = System.getenv("GIGACHAT_API_KEY") 
            ?: throw IllegalStateException(
                "GIGACHAT_API_KEY не установлен. " +
                "Установите переменную окружения: export GIGACHAT_API_KEY=your_authorization_key"
            )
    
    val gigachatApiUrl: String
        get() = System.getenv("GIGACHAT_API_URL") ?: DEFAULT_GIGACHAT_API_URL
    
    val huggingFaceApiKey: String?
        get() = System.getenv("HUGGINGFACE_API_KEY")
    
    val huggingFaceApiUrl: String
        get() = System.getenv("HUGGINGFACE_API_URL") ?: DEFAULT_HUGGINGFACE_API_URL
    
    val serverPort: Int
        get() = System.getenv("SERVER_PORT")?.toIntOrNull() ?: DEFAULT_SERVER_PORT
    
    val maxTokens: Int
        get() = DEFAULT_MAX_TOKENS
    
    /**
     * URL MCP (Model Context Protocol) сервера
     */
    val mcpServerUrl: String
        get() = System.getenv("MCP_SERVER_URL") ?: DEFAULT_MCP_SERVER_URL

    /**
     * Периодическая сводка задач (через /api/chat/tools -> MCP reminder tool)
     */
    val reminderSummaryEnabled: Boolean
        get() = (System.getenv("REMINDER_SUMMARY_ENABLED") ?: DEFAULT_REMINDER_SUMMARY_ENABLED.toString()).toBoolean()

    val reminderSummaryIntervalMs: Long
        get() = System.getenv("REMINDER_SUMMARY_INTERVAL_MS")?.toLongOrNull() ?: DEFAULT_REMINDER_SUMMARY_INTERVAL_MS

    val reminderSummaryVendor: String?
        get() = System.getenv("REMINDER_SUMMARY_VENDOR") ?: "perplexity"

    val reminderSummaryModel: String?
        get() = System.getenv("REMINDER_SUMMARY_MODEL") ?: DEFAULT_MODEL

    val reminderSummaryMaxToolIterations: Int
        get() = System.getenv("REMINDER_SUMMARY_MAX_TOOL_ITERATIONS")?.toIntOrNull() ?: 10

    /**
     * Telegram
     */
    val telegramBotToken: String?
        get() = System.getenv("TELEGRAM_BOT_TOKEN")

    val telegramChatId: String?
        get() = System.getenv("TELEGRAM_CHAT_ID")
}


