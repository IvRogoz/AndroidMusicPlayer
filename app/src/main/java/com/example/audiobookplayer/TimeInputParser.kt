package com.example.audiobookplayer

object TimeInputParser {
    fun parseMilliseconds(value: String): Long? {
        val parts = value.split(':').map { it.trim() }
        if (parts.isEmpty() || parts.size > 3 || parts.any { it.isEmpty() }) {
            return null
        }
        val numbers = parts.map { part ->
            part.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        }
        if (numbers.size >= 2 && numbers.last() !in 0L..59L) {
            return null
        }
        if (numbers.size == 3 && numbers[1] !in 0L..59L) {
            return null
        }
        return try {
            val seconds = when (numbers.size) {
                1 -> numbers[0]
                2 -> Math.addExact(Math.multiplyExact(numbers[0], 60L), numbers[1])
                3 -> Math.addExact(
                    Math.addExact(Math.multiplyExact(numbers[0], 3600L), Math.multiplyExact(numbers[1], 60L)),
                    numbers[2]
                )
                else -> return null
            }
            Math.multiplyExact(seconds, 1000L)
        } catch (exception: ArithmeticException) {
            null
        }
    }
}
