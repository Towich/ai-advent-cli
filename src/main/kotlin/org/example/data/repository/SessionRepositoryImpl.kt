package org.example.data.repository

import org.example.data.service.MemoryStorageService
import org.example.domain.model.DialogSession
import org.example.domain.repository.SessionRepository
import org.slf4j.LoggerFactory

/**
 * Реализация репозитория для хранения одной сессии в памяти с сохранением в JSON-файл
 */
class SessionRepositoryImpl(
    private val memoryStorageService: MemoryStorageService = MemoryStorageService()
) : SessionRepository {
    private val logger = LoggerFactory.getLogger(SessionRepositoryImpl::class.java)
    
    @Volatile
    private var currentSession: DialogSession? = null
    
    init {
        // Загружаем сессию из файла при инициализации
        loadSessionFromStorage()
    }
    
    override fun createOrResetSession(
        systemPrompt: String?,
        model: String,
        maxTokens: Int,
        disableSearch: Boolean,
        maxRounds: Int,
        initialUserMessage: String
    ): DialogSession {
        val now = System.currentTimeMillis()
        
        val session = DialogSession(
            systemPrompt = systemPrompt,
            messages = mutableListOf(),
            currentRound = 0,
            maxRounds = maxRounds,
            model = model,
            maxTokens = maxTokens,
            disableSearch = disableSearch,
            createdAt = now,
            lastActivityAt = now,
            initialUserMessage = initialUserMessage,
            isComplete = false,
            accumulatedTotalTokens = 0
        )
        
        currentSession = session
        saveSessionToStorage()
        logger.info("Создана новая сессия диалога: модель=$model, maxRounds=$maxRounds, maxTokens=$maxTokens, disableSearch=$disableSearch")
        return session
    }
    
    override fun getSession(): DialogSession? {
        return currentSession
    }
    
    /**
     * Обновляет текущую сессию и сохраняет её в файл
     */
    override fun updateSession(session: DialogSession) {
        currentSession = session
        saveSessionToStorage()
    }
    
    override fun clearSession() {
        logger.debug("Сессия диалога очищена")
        currentSession = null
        saveSessionToStorage() // Сохраняем null, чтобы удалить файл
    }
    
    override fun shutdown() {
        // Сохраняем сессию перед завершением
        saveSessionToStorage()
        logger.debug("SessionRepository остановлен, сессия сохранена")
    }
    
    /**
     * Загружает сессию из файла при старте
     */
    private fun loadSessionFromStorage() {
        memoryStorageService.loadSession()
            .onSuccess { session ->
                if (session != null) {
                    currentSession = session
                    logger.info("Сессия восстановлена из внешней памяти: round=${session.currentRound}/${session.maxRounds}, messages=${session.messages.size}")
                } else {
                    logger.debug("Сохраненная сессия не найдена")
                }
            }
            .onFailure { error ->
                logger.warn("Не удалось загрузить сессию из файла: ${error.message}")
            }
    }
    
    /**
     * Сохраняет текущую сессию в файл
     */
    private fun saveSessionToStorage() {
        memoryStorageService.saveSession(currentSession)
            .onFailure { error ->
                logger.warn("Не удалось сохранить сессию в файл: ${error.message}")
            }
    }
}


