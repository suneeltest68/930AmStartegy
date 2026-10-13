package com.example

import com.example.viewmodel.AuthViewModel
import com.example.util.AuthUtils
import com.example.service.LiveTradingEngine
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Scanner

fun main() {
    val dotenv = dotenv {
        directory = if (File(".env").exists()) "." else ".."
        ignoreIfMissing = true
    }
    val viewModel = AuthViewModel()
    val scanner = Scanner(System.`in`)

    println("==================================================")
    println("🟢 LOCAL IDE LIVE TRADING - 9:25 AM BREAKOUT STRATEGY")
    println("==================================================")

    val appId = System.getenv("FYERS_APP_ID") ?: dotenv["FYERS_APP_ID"] ?: ""
    val secretKey = System.getenv("FYERS_SECRET_KEY") ?: dotenv["FYERS_SECRET_KEY"] ?: ""
    val redirectUri = System.getenv("FYERS_REDIRECT_URI") ?: dotenv["FYERS_REDIRECT_URI"] ?: ""

    if (appId.isEmpty() || secretKey.isEmpty() || redirectUri.isEmpty()) {
        println("❌ Error: Missing Fyers environment variables (FYERS_APP_ID, FYERS_SECRET_KEY, FYERS_REDIRECT_URI) in .env.")
        return
    }

    var token = viewModel.getCachedToken() ?: dotenv["FYERS_ACCESS_TOKEN"]
    if (token != null) {
        println("[Cache] Found valid Access Token!")
    } else {
        println("\n[Auth] No cached token found. Initiating interactive local IDE login...")
        viewModel.prepareLoginUrl(appId, redirectUri)
        val loginUrl = viewModel.authState.value.loginUrl
        println("\n1. Open this URL in your browser to authorize:\n$loginUrl")
        print("\n2. Paste either the full redirect URL or the 'auth_code': ")

        var input = scanner.nextLine().trim()
        while (input.isEmpty()) {
            print("Input cannot be empty. Please paste the redirect URL or auth_code: ")
            input = scanner.nextLine().trim()
        }

        val authCode = AuthUtils.extractAuthCode(input)
        println("\n[Auth] Exchanging auth code for access token...")
        runBlocking {
            viewModel.authenticateWithAuthCode(appId, secretKey, authCode)
        }

        token = viewModel.authState.value.accessToken
        if (token == null) {
            println("❌ Authentication failed: ${viewModel.authState.value.errorMessage}")
            return
        }
        println("✅ Successfully authenticated and cached access token!")
    }

    runBlocking {
        val liveEngine = LiveTradingEngine(viewModel)
        liveEngine.executeLiveStrategy(appId, token)
    }
}
