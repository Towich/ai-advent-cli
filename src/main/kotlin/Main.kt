package org.example

import kotlinx.coroutines.*

fun main() = runBlocking {
    println("=== AI Chat CLI ===")
    println("Введите ваш вопрос или 'exit' для выхода")
    println()
    
    val apiClient = ApiClient()
    
    try {
        while (true) {
            val input = InputHandler.readInput() ?: continue
            
            if (InputHandler.isExitCommand(input)) {
                println("До свидания!")
                break
            }
            
            // Показываем индикатор загрузки в отдельной корутине
            val loadingJob = launch {
                while (true) {
                    print("AI думает")
                    repeat(3) {
                        delay(300)
                        print(".")
                    }
                    print("\r")
                    delay(100)
                }
            }
            
            // Отправляем запрос к API
            val response = withContext(Dispatchers.IO) {
                apiClient.sendMessage(input)
            }
            
            loadingJob.cancel()
            print("\r") // Очищаем строку с индикатором
            
            println("AI: $response")
            println()
        }
    } catch (e: IllegalStateException) {
        println("Ошибка конфигурации: ${e.message}")
    } catch (e: Exception) {
        println("Произошла ошибка: ${e.message}")
        e.printStackTrace()
    } finally {
        apiClient.close()
    }
}
