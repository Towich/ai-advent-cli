package org.example

object InputHandler {
    private val exitCommands = setOf("exit", "quit", "q", "выход")
    
    fun readInput(): String? {
        print("Вы: ")
        return readlnOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
    
    fun isExitCommand(input: String): Boolean {
        return exitCommands.contains(input.lowercase())
    }
}

