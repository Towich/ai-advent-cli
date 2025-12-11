package org.example.domain.service

import org.example.domain.model.Message

/**
 * Сервис для подсчета символов в сообщениях
 */
object MessageCharacterCounter {
    /**
     * Подсчитывает общее количество символов во всех полях content сообщений
     * 
     * @param messages список сообщений для подсчета
     * @return общее количество символов
     */
    fun countTotalCharacters(messages: List<Message>): Int {
        return messages.sumOf { it.content.length }
    }
    
    /**
     * Подсчитывает количество символов в сообщениях определенной роли
     * 
     * @param messages список сообщений
     * @param role роль сообщений для подсчета
     * @return количество символов
     */
    fun countCharactersByRole(messages: List<Message>, role: String): Int {
        return messages
            .filter { it.role == role }
            .sumOf { it.content.length }
    }
}
