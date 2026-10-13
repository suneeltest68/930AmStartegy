package com.example.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object TelegramNotifier {
    suspend fun sendAlert(message: String) {
        val botToken = System.getenv("TELEGRAM_BOT_TOKEN") ?: ""
        val chatId = System.getenv("TELEGRAM_CHAT_ID") ?: ""
        if (botToken.isBlank() || chatId.isBlank()) {
            println("[TelegramNotifier] Skipping alert (token/chatId not configured): $message")
            return
        }
        withContext(Dispatchers.IO) {
            try {
                val encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8.name())
                val urlString = "https://api.telegram.org/bot$botToken/sendMessage?chat_id=$chatId&text=$encodedMessage"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    println("Telegram alert failed with response code: $responseCode")
                }
            } catch (e: Exception) {
                println("Failed to send Telegram alert: ${e.message}")
            }
        }
    }
}
