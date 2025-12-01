package org.example

object Config {
    private const val DEFAULT_API_URL = "https://api.perplexity.ai/chat/completions"
    private const val DEFAULT_MODEL = "sonar"
    private const val DEFAULT_SERVER_PORT = 8080
    
    val apiKey: String
        get() = System.getenv("PERPLEXITY_API_KEY") 
            ?: throw IllegalStateException(
                "PERPLEXITY_API_KEY не установлен. " +
                "Установите переменную окружения: export PERPLEXITY_API_KEY=your_api_key"
            )
    
    val apiUrl: String
        get() = System.getenv("PERPLEXITY_API_URL") ?: DEFAULT_API_URL
    
    val model: String
        get() = System.getenv("PERPLEXITY_MODEL") ?: DEFAULT_MODEL
    
    val serverPort: Int
        get() = System.getenv("SERVER_PORT")?.toIntOrNull() ?: DEFAULT_SERVER_PORT
}

