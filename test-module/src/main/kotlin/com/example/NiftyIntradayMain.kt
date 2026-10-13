package com.example

import com.example.service.NiftyIntradayStrategyEngine
import com.example.util.AuthUtils
import com.example.viewmodel.AuthViewModel
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Scanner

fun main() = runBlocking {
    val dotenv = dotenv {
        directory = if (File(".env").exists()) "." else ".."
        ignoreIfMissing = true
    }
    val viewModel = AuthViewModel()
    val strategyEngine = NiftyIntradayStrategyEngine(viewModel)
    val scanner = Scanner(System.`in`)

    println("==================================================")
    println("🚀 FYERS NIFTY INTRADAY DUAL-CONFIRMATION STRATEGY")
    println("==================================================")

    val appId = System.getenv("FYERS_APP_ID") ?: dotenv["FYERS_APP_ID"]
    val secretKey = System.getenv("FYERS_SECRET_KEY") ?: dotenv["FYERS_SECRET_KEY"]
    val redirectUri = System.getenv("FYERS_REDIRECT_URI") ?: dotenv["FYERS_REDIRECT_URI"] ?: "https://trade.fyers.in/api-login/redirect-uri/index.html"

    if (appId.isNullOrEmpty() || secretKey.isNullOrEmpty()) {
        println("❌ Error: Missing FYERS_APP_ID or FYERS_SECRET_KEY in .env file.")
        return@runBlocking
    }

    var token = viewModel.getCachedToken() ?: System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"]

    if (token.isNullOrEmpty()) {
        println("\n[Auth] No valid Access Token found. Initiating login flow...")
        viewModel.prepareLoginUrl(appId, redirectUri)
        val loginUrl = viewModel.authState.value.loginUrl
        println("1. Open this URL in your browser to authorize:\n$loginUrl")
        print("2. Paste either the full redirect URL or the 'auth_code': ")

        var input = scanner.nextLine().trim()
        while (input.isEmpty()) {
            print("Input cannot be empty. Please paste the redirect URL or auth_code: ")
            input = scanner.nextLine().trim()
        }

        val authCode = AuthUtils.extractAuthCode(input)
        println("\n[Auth] Exchanging auth code for access token...")
        viewModel.authenticateWithAuthCode(appId, secretKey, authCode)

        token = viewModel.authState.value.accessToken
        if (token.isNullOrEmpty()) {
            println("❌ Authentication failed: ${viewModel.authState.value.errorMessage}")
            return@runBlocking
        }
        println("✅ Successfully acquired Access Token!")
    } else {
        println("[Auth] Using valid cached or environment Access Token.")
    }

    val startDate = System.getenv("START_DATE") ?: dotenv["START_DATE"] ?: "2026-10-01"
    val endDate = System.getenv("END_DATE") ?: dotenv["END_DATE"] ?: "2026-10-13"
    val spotBoxPercent = (System.getenv("SPOT_BOX_PERCENT") ?: dotenv["SPOT_BOX_PERCENT"] ?: "0.05").toDouble()
    val optionBoxPercent = (System.getenv("OPTION_BOX_PERCENT") ?: dotenv["OPTION_BOX_PERCENT"] ?: "3.0").toDouble()

    strategyEngine.runIntradayBacktest(
        appId = appId,
        accessToken = token,
        startDate = startDate,
        endDate = endDate,
        spotBoxPercent = spotBoxPercent,
        optionBoxPercent = optionBoxPercent,
        quantity = 65
    )
}
