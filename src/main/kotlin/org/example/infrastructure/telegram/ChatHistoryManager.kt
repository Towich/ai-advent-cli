package org.example.infrastructure.telegram

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.example.domain.model.Message
import org.example.domain.model.TokenUsage
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Менеджер для сохранения и загрузки истории диалогов в JSON файлы
 */
class ChatHistoryManager(
    private val storageDir: String = "telegram_chat_history"
) {
    private val logger = LoggerFactory.getLogger(ChatHistoryManager::class.java)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    init {
        // Создаем директорию для хранения истории, если её нет
        val dir = File(storageDir)
        if (!dir.exists()) {
            dir.mkdirs()
            logger.info("Создана директория для истории чатов: $storageDir")
        }
    }
    
    /**
     * Структура истории диалога для сериализации
     */
    @Serializable
    data class ChatHistory(
        val chatId: Long,
        val messages: List<ChatMessage>,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis()
    )
    
    @Serializable
    data class ChatMessage(
        val role: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val tokenUsage: TokenUsageData? = null
    )
    
    @Serializable
    data class TokenUsageData(
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val totalTokens: Int? = null,
        val cost: Double? = null
    )
    
    /**
     * Получает путь к файлу активной истории для конкретного чата
     */
    private fun getActiveHistoryFilePath(chatId: Long): String {
        return "$storageDir/chat_$chatId.json"
    }
    
    /**
     * Получает путь к архивированному файлу истории с timestamp
     */
    private fun getArchivedHistoryFilePath(chatId: Long, timestamp: Long): String {
        return "$storageDir/chat_${chatId}_${timestamp}.json"
    }
    
    /**
     * Загружает активную историю диалога для чата
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun loadHistory(chatId: Long): ChatHistory? {
        return try {
            val file = File(getActiveHistoryFilePath(chatId))
            if (!file.exists()) {
                logger.debug("Файл активной истории не найден для chatId=$chatId")
                return null
            }
            
            file.inputStream().use { input ->
                val history = json.decodeFromStream<ChatHistory>(input)
                logger.info("Загружена активная история для chatId=$chatId: ${history.messages.size} сообщений")
                history
            }
        } catch (e: Exception) {
            logger.error("Ошибка при загрузке истории для chatId=$chatId: ${e.message}", e)
            null
        }
    }
    
    /**
     * Сохраняет активную историю диалога
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun saveHistory(history: ChatHistory) {
        try {
            val file = File(getActiveHistoryFilePath(history.chatId))
            file.outputStream().use { output ->
                json.encodeToStream(history.copy(updatedAt = System.currentTimeMillis()), output)
            }
            logger.debug("Активная история сохранена для chatId=${history.chatId}: ${history.messages.size} сообщений")
        } catch (e: Exception) {
            logger.error("Ошибка при сохранении истории для chatId=${history.chatId}: ${e.message}", e)
        }
    }
    
    /**
     * Добавляет сообщение в историю
     */
    fun addMessage(chatId: Long, role: String, content: String, tokenUsage: TokenUsage? = null) {
        val history = loadHistory(chatId) ?: ChatHistory(
            chatId = chatId,
            messages = emptyList()
        )
        
        val tokenUsageData = tokenUsage?.let {
            TokenUsageData(
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens,
                cost = it.cost
            )
        }
        
        val newMessage = ChatMessage(
            role = role,
            content = content,
            timestamp = System.currentTimeMillis(),
            tokenUsage = tokenUsageData
        )
        
        val updatedHistory = history.copy(
            messages = history.messages + newMessage,
            updatedAt = System.currentTimeMillis()
        )
        
        saveHistory(updatedHistory)
    }
    
    /**
     * Получает список сообщений из истории для использования в API
     */
    fun getMessages(chatId: Long): List<Message> {
        val history = loadHistory(chatId) ?: return emptyList()
        return history.messages.map { 
            Message(role = it.role, content = it.content)
        }
    }
    
    /**
     * Архивирует текущий диалог (переименовывает файл с timestamp) и очищает активную историю
     */
    fun archiveDialog(chatId: Long): String? {
        return try {
            val activeFile = File(getActiveHistoryFilePath(chatId))
            if (!activeFile.exists()) {
                logger.debug("Нет активной истории для архивирования chatId=$chatId")
                return null
            }
            
            // Загружаем историю, чтобы получить createdAt
            val history = loadHistory(chatId)
            val timestamp = history?.createdAt ?: System.currentTimeMillis()
            
            // Переименовываем файл с timestamp
            val archivedFile = File(getArchivedHistoryFilePath(chatId, timestamp))
            activeFile.renameTo(archivedFile)
            
            if (archivedFile.exists()) {
                val fileName = archivedFile.name
                logger.info("Диалог архивирован для chatId=$chatId: $fileName (${history?.messages?.size ?: 0} сообщений)")
                fileName
            } else {
                logger.warn("Не удалось переименовать файл истории для chatId=$chatId")
                null
            }
        } catch (e: Exception) {
            logger.error("Ошибка при архивировании диалога для chatId=$chatId: ${e.message}", e)
            null
        }
    }
    
    /**
     * Очищает активную историю диалога (удаляет активный файл)
     */
    fun clearActiveHistory(chatId: Long) {
        try {
            val file = File(getActiveHistoryFilePath(chatId))
            if (file.exists()) {
                file.delete()
                logger.info("Активная история очищена для chatId=$chatId")
            }
        } catch (e: Exception) {
            logger.error("Ошибка при очистке активной истории для chatId=$chatId: ${e.message}", e)
        }
    }
    
    /**
     * Очищает историю диалога (устаревший метод, используйте clearActiveHistory)
     */
    @Deprecated("Используйте clearActiveHistory или archiveDialog")
    fun clearHistory(chatId: Long) {
        clearActiveHistory(chatId)
    }
    
    /**
     * Преобразует TokenUsage в TokenUsageData
     */
    private fun TokenUsage.toTokenUsageData(): TokenUsageData {
        return TokenUsageData(
            promptTokens = this.promptTokens,
            completionTokens = this.completionTokens,
            totalTokens = this.totalTokens,
            cost = this.cost
        )
    }
}

