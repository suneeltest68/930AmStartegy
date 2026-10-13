package com.example

import com.example.renko.service.RenkoMasterStrategyResult
import com.example.renko.service.RenkoMasterSuiteEngine
import com.example.viewmodel.AuthViewModel
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking
import java.io.File

fun main() = runBlocking {
    val dotenv = dotenv {
        directory = if (File(".env").exists()) "." else ".."
        ignoreIfMissing = true
    }
    val viewModel = AuthViewModel()
    val engine = RenkoMasterSuiteEngine(viewModel)

    println("==================================================")
    println("🧱 RENKO MASTER SUITE - ALL 13 PDF STRATEGIES RUNNER")
    println("==================================================")

    val appId = System.getenv("FYERS_APP_ID") ?: dotenv["FYERS_APP_ID"] ?: ""
    val token = viewModel.getCachedToken() ?: System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"] ?: ""

    val startDate = System.getenv("START_DATE") ?: dotenv["START_DATE"] ?: "2026-09-01"
    val endDate = System.getenv("END_DATE") ?: dotenv["END_DATE"] ?: "2026-09-30"

    val results = mutableListOf<RenkoMasterStrategyResult>()

    for (stratNum in 1..13) {
        val symbol = when (stratNum) {
            4, 5, 6 -> "NSE:NIFTYBANK-INDEX"
            else -> "NSE:NIFTY50-INDEX"
        }
        val qty = if (stratNum in listOf(4, 5, 6)) 15 else 65

        val res = engine.runStrategy(
            appId = appId,
            accessToken = token,
            strategyNumber = stratNum,
            symbol = symbol,
            startDate = startDate,
            endDate = endDate,
            quantity = qty
        )
        results.add(res)
    }

    println("\n" + "=".repeat(125))
    println("📊 RENKO MASTER SUITE: ALL 13 STRATEGIES COMPARISON REPORT ($startDate TO $endDate)")
    println("=".repeat(125))
    println("%-12s | %-36s | %-10s | %-5s | %-6s | %-6s | %-10s | %-12s".format(
        "Strategy #", "Strategy Name", "Type", "Qty", "Trades", "Win %", "P&L (₹)", "Max DD (₹)"
    ))
    println("-".repeat(125))

    for (r in results) {
        println("%-12s | %-36s | %-10s | %-5d | %-6d | %-6.1f | ₹%-11.2f | ₹%-11.2f".format(
            "Strategy ${r.strategyNumber}",
            r.strategyName.take(36),
            r.category,
            r.quantity,
            r.totalTrades,
            r.winRatePercent,
            r.totalPnlRupees,
            r.maxDrawdownRupees
        ))
    }
    println("=".repeat(125) + "\n")
}
