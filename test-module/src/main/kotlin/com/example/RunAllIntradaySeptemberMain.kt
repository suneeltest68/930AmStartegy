package com.example

import com.example.renko.service.AllIntradayStrategiesEngine
import com.example.renko.service.IntradayStrategyRunSummary
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
    val engine = AllIntradayStrategiesEngine(viewModel)

    println("==================================================")
    println("🧱 RUNNING ALL RENKO.PDF INTRADAY STRATEGIES")
    println("📅 Period: 1st September 2026 to 30th September 2026")
    println("==================================================")

    val appId = System.getenv("FYERS_APP_ID") ?: dotenv["FYERS_APP_ID"] ?: ""
    val token = viewModel.getCachedToken() ?: System.getenv("FYERS_ACCESS_TOKEN") ?: dotenv["FYERS_ACCESS_TOKEN"] ?: ""

    val startDate = "2026-05-01"
    val endDate = "2026-05-31"

    val intradayStrategyNumbers = listOf(1, 4, 7, 10, 11, 12)
    val summaries = mutableListOf<IntradayStrategyRunSummary>()

    for (stratNum in intradayStrategyNumbers) {
        val isBankNifty = stratNum == 4
        val symbol = if (isBankNifty) "NSE:NIFTYBANK-INDEX" else "NSE:NIFTY50-INDEX"
        val qty = if (isBankNifty) 15 else 65 // 1 Nifty Lot = 65 Qty, 1 BankNifty Lot = 15 Qty

        val summary = engine.runExactPdfIntradayStrategy(
            appId = appId,
            accessToken = token,
            strategyNumber = stratNum,
            symbol = symbol,
            startDate = startDate,
            endDate = endDate,
            quantity = qty
        )
        summaries.add(summary)
    }

    println("\n" + "=".repeat(120))
    println("📊 ALL INTRADAY STRATEGIES COMPARISON REPORT (1 LOT QUANTITY: NIFTY=65, BANKNIFTY=15)")
    println("=".repeat(120))
    println("%-12s | %-38s | %-5s | %-6s | %-6s | %-10s | %-12s | %-12s | %-12s".format(
        "Strategy #", "Strategy Name", "Qty", "Trades", "Win %", "P&L (Pts)", "P&L (₹)", "Max DD (Pts)", "Max DD (₹)"
    ))
    println("-".repeat(120))

    for (s in summaries) {
        println("%-12s | %-38s | %-5d | %-6d | %-6.1f | %-10.2f | ₹%-11.2f | %-12.2f | ₹%-11.2f".format(
            "Strategy ${s.strategyNumber}",
            s.strategyName.take(38),
            s.quantity,
            s.totalTrades,
            s.winRatePercent,
            s.totalPnlPoints,
            s.totalPnlRupees,
            s.maxDrawdownPoints,
            s.maxDrawdownRupees
        ))
    }
    println("=".repeat(120) + "\n")
}
