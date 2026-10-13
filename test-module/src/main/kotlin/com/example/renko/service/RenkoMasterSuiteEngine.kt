package com.example.renko.service

import com.example.Candle
import com.example.renko.engine.RenkoChartEngine
import com.example.renko.model.*
import com.example.renko.strategy.*
import com.example.viewmodel.AuthViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class RenkoMasterStrategyResult(
    val strategyNumber: Int,
    val strategyName: String,
    val category: String, // Intraday, Positional, ETF
    val symbol: String,
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRatePercent: Double,
    val totalPnlPoints: Double,
    val totalPnlRupees: Double,
    val maxDrawdownPoints: Double,
    val maxDrawdownRupees: Double,
    val quantity: Int,
    val trades: List<RenkoTradeDetail>
)

class RenkoMasterSuiteEngine(private val viewModel: AuthViewModel) {

    /**
     * Executes any strategy (1 through 13) from Mukta Dhamankar's Renko Charts guide.
     */
    suspend fun runStrategy(
        appId: String,
        accessToken: String,
        strategyNumber: Int,
        symbol: String,
        startDate: String,
        endDate: String,
        quantity: Int = 65
    ): RenkoMasterStrategyResult {
        val name = getStrategyName(strategyNumber)
        val category = getStrategyCategory(strategyNumber)
        val isOption = strategyNumber in listOf(10, 11, 12)
        val isDaily = strategyNumber in listOf(8, 13)

        println("==================================================")
        println("🧱 RENKO MASTER SUITE: STRATEGY $strategyNumber ($name)")
        println("📌 Category: $category | Symbol: $symbol | Period: $startDate to $endDate")
        println("==================================================")

        val resolution = if (isDaily) "D" else "1"
        val boxSizePercent = getStrategyBoxSize(strategyNumber, symbol)

        if (isOption) {
            val allOptionEngine = AllIntradayStrategiesEngine(viewModel)
            val summary = allOptionEngine.runExactPdfIntradayStrategy(
                appId, accessToken, strategyNumber, symbol, startDate, endDate, quantity
            )
            return RenkoMasterStrategyResult(
                strategyNumber = strategyNumber,
                strategyName = name,
                category = category,
                symbol = symbol,
                totalTrades = summary.totalTrades,
                winningTrades = summary.winningTrades,
                losingTrades = summary.losingTrades,
                winRatePercent = summary.winRatePercent,
                totalPnlPoints = summary.totalPnlPoints,
                totalPnlRupees = summary.totalPnlRupees,
                maxDrawdownPoints = summary.maxDrawdownPoints,
                maxDrawdownRupees = summary.maxDrawdownRupees,
                quantity = quantity,
                trades = summary.trades
            )
        }

        val allCandles = mutableListOf<Candle>()
        var currDate = LocalDate.parse(startDate)
        val finalDate = LocalDate.parse(endDate)

        while (!currDate.isAfter(finalDate)) {
            val dayStr = currDate.toString()
            if (currDate.dayOfWeek == java.time.DayOfWeek.SATURDAY || currDate.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
                currDate = currDate.plusDays(1)
                continue
            }

            val histData = viewModel.fetchHistoricalData(
                appId = appId,
                accessToken = accessToken,
                symbol = symbol,
                resolution = resolution,
                rangeFrom = dayStr,
                rangeTo = dayStr
            )

            if (histData != null && histData.has("candles")) {
                val arr = histData.getJSONArray("candles")
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONArray(i)
                    val epoch = c.getLong(0)
                    val open = c.getDouble(1)
                    val high = c.getDouble(2)
                    val low = c.getDouble(3)
                    val close = c.getDouble(4)
                    val volume = c.getDouble(5)

                    val timestamp = Instant.ofEpochSecond(epoch)
                        .atZone(ZoneId.of("Asia/Kolkata"))
                        .toLocalDateTime()
                        .toString()

                    allCandles.add(
                        Candle(
                            timestamp = timestamp,
                            open = open,
                            high = high,
                            low = low,
                            close = close,
                            volume = volume,
                            epoch = epoch
                        )
                    )
                }
            }

            currDate = currDate.plusDays(1)
        }

        if (allCandles.isEmpty()) {
            return emptyResult(strategyNumber, name, category, symbol, quantity)
        }

        val renkoEngine = RenkoChartEngine(renkoType = RenkoType.PERCENTAGE, value = boxSizePercent)
        val bricks = renkoEngine.buildBricks(allCandles)

        if (bricks.size < 10) {
            return emptyResult(strategyNumber, name, category, symbol, quantity)
        }

        val signals: List<RenkoTradeSignal> = when (strategyNumber) {
            1 -> RenkoStrategies.evaluateStrategy1DmaIntraday(symbol, bricks)
            2 -> RenkoStrategies.evaluateStrategy2DmaPositional(symbol, bricks)
            3 -> RenkoStrategies.evaluateStrategy3SuperTrend(symbol, bricks, 40, 12.0)
            4 -> RenkoStrategies.evaluateStrategy4BankNiftyDmaIntraday(symbol, bricks)
            5 -> RenkoStrategies.evaluateStrategy5BankNiftyDmaPositional(symbol, bricks)
            6 -> RenkoStrategies.evaluateStrategy6BankNiftySuperTrendPositional(symbol, bricks)
            7 -> RenkoStrategies.evaluateStrategy7MultiSwing(symbol, bricks)
            8 -> RenkoStrategies.evaluateStrategy8TopDown(symbol, bricks, true)
            9 -> RenkoStrategies.evaluateStrategy9TmaRsi(symbol, bricks)
            13 -> RenkoStrategies.evaluateStrategy13EtfBuying(symbol, bricks)
            else -> RenkoStrategies.evaluateStrategy1DmaIntraday(symbol, bricks)
        }

        return simulateTrades(strategyNumber, name, category, symbol, bricks, signals, quantity)
    }

    private fun simulateTrades(
        strategyNumber: Int,
        name: String,
        category: String,
        symbol: String,
        bricks: List<RenkoBrick>,
        signals: List<RenkoTradeSignal>,
        quantity: Int
    ): RenkoMasterStrategyResult {
        val trades = mutableListOf<RenkoTradeDetail>()
        var activeSignal: RenkoTradeSignal? = null
        var peakPnL = 0.0
        var maxDrawdown = 0.0

        for (brick in bricks) {
            if (activeSignal == null) {
                val matchSig = signals.firstOrNull { it.timestamp == brick.timestamp }
                if (matchSig != null) {
                    activeSignal = matchSig
                }
            } else {
                val sig = activeSignal
                var isClosed = false
                var exitPrice = brick.close
                var exitReason = ""

                if (sig.action == TradeAction.BUY) {
                    if (brick.low <= sig.stopLoss) {
                        isClosed = true
                        exitPrice = sig.stopLoss
                        exitReason = "Stop Loss Hit"
                    } else if (sig.target != null && brick.high >= sig.target) {
                        isClosed = true
                        exitPrice = sig.target
                        exitReason = "Target Reached"
                    }
                } else if (sig.action == TradeAction.SELL) {
                    if (brick.high >= sig.stopLoss) {
                        isClosed = true
                        exitPrice = sig.stopLoss
                        exitReason = "Stop Loss Hit"
                    } else if (sig.target != null && brick.low <= sig.target) {
                        isClosed = true
                        exitPrice = sig.target
                        exitReason = "Target Reached"
                    }
                }

                if (isClosed) {
                    val pnlPoints = if (sig.action == TradeAction.BUY) exitPrice - sig.entryPrice else sig.entryPrice - exitPrice
                    val pnlAmount = pnlPoints * quantity

                    trades.add(
                        RenkoTradeDetail(
                            entryTimestamp = sig.timestamp,
                            exitTimestamp = brick.timestamp,
                            action = sig.action,
                            entryPrice = sig.entryPrice,
                            exitPrice = exitPrice,
                            stopLoss = sig.stopLoss,
                            target = sig.target,
                            pnlPoints = pnlPoints,
                            pnlAmount = pnlAmount,
                            exitReason = exitReason
                        )
                    )

                    val cumulativePnL = trades.sumOf { it.pnlPoints }
                    if (cumulativePnL > peakPnL) peakPnL = cumulativePnL
                    val currentDd = peakPnL - cumulativePnL
                    if (currentDd > maxDrawdown) maxDrawdown = currentDd

                    activeSignal = null
                }
            }
        }

        val winningCount = trades.count { it.pnlPoints > 0 }
        val losingCount = trades.count { it.pnlPoints < 0 }
        val winRate = if (trades.isNotEmpty()) (winningCount.toDouble() / trades.size) * 100.0 else 0.0
        val totalPoints = trades.sumOf { it.pnlPoints }
        val totalAmount = trades.sumOf { it.pnlAmount }

        val res = RenkoMasterStrategyResult(
            strategyNumber = strategyNumber,
            strategyName = name,
            category = category,
            symbol = symbol,
            totalTrades = trades.size,
            winningTrades = winningCount,
            losingTrades = losingCount,
            winRatePercent = winRate,
            totalPnlPoints = totalPoints,
            totalPnlRupees = totalAmount,
            maxDrawdownPoints = maxDrawdown,
            maxDrawdownRupees = maxDrawdown * quantity,
            quantity = quantity,
            trades = trades
        )

        printReport(res)
        return res
    }

    private fun getStrategyCategory(num: Int): String {
        return when (num) {
            1, 4, 7, 10, 11, 12 -> "Intraday"
            2, 3, 5, 6, 8, 9 -> "Positional"
            13 -> "ETF"
            else -> "General"
        }
    }

    private fun getStrategyName(num: Int): String {
        return when (num) {
            1 -> "Trading NF with DMA (Intraday)"
            2 -> "Trading NF with DMA (Positional)"
            3 -> "Trading NF with SuperTrend (Positional)"
            4 -> "Trading BNF with DMA (Intraday)"
            5 -> "Trading BNF with DMA (Positional)"
            6 -> "Trading BNF with SuperTrend (Positional)"
            7 -> "Multi Swing Breakout/Breakdown Strategy"
            8 -> "Top Down Trading Strategy"
            9 -> "TMA + RSI Strategy (Positional)"
            10 -> "DC & Option Buying Nifty Strategy"
            11 -> "Option Buying Strategy (ARSI)"
            12 -> "Option Selling Strategy (ARSI)"
            13 -> "Buying ETFs Strategy"
            else -> "Strategy $num"
        }
    }

    private fun getStrategyBoxSize(num: Int, symbol: String): Double {
        val isBankNifty = symbol.contains("BANKNIFTY", ignoreCase = true) || symbol.contains("NIFTYBANK", ignoreCase = true)
        return when (num) {
            1 -> 0.05
            2 -> 0.10
            3 -> 0.10
            4 -> 0.08
            5 -> 0.15
            6 -> 0.15
            7 -> if (isBankNifty) 0.08 else 0.05
            8 -> 0.05
            9 -> 1.00
            10 -> 0.075
            11 -> 3.00
            12 -> 3.00
            13 -> 0.25
            else -> 0.05
        }
    }

    private fun emptyResult(num: Int, name: String, category: String, symbol: String, quantity: Int): RenkoMasterStrategyResult {
        return RenkoMasterStrategyResult(
            strategyNumber = num,
            strategyName = name,
            category = category,
            symbol = symbol,
            totalTrades = 0,
            winningTrades = 0,
            losingTrades = 0,
            winRatePercent = 0.0,
            totalPnlPoints = 0.0,
            totalPnlRupees = 0.0,
            maxDrawdownPoints = 0.0,
            maxDrawdownRupees = 0.0,
            quantity = quantity,
            trades = emptyList()
        )
    }

    private fun printReport(r: RenkoMasterStrategyResult) {
        println("\n==================================================")
        println("📊 RENKO MASTER SUITE REPORT: STRATEGY ${r.strategyNumber}")
        println("==================================================")
        println("Strategy: ${r.strategyName} (${r.category})")
        println("Symbol: ${r.symbol} | Lot Size: 1 Lot (${r.quantity} Qty) | Total Trades: ${r.totalTrades}")
        println("Wins: ${r.winningTrades} | Losses: ${r.losingTrades} | Win Rate: %.2f%%".format(r.winRatePercent))
        println("Total P&L: %.2f Points | ₹%.2f".format(r.totalPnlPoints, r.totalPnlRupees))
        println("Max Drawdown: %.2f Points | ₹%.2f".format(r.maxDrawdownPoints, r.maxDrawdownRupees))
        println("==================================================\n")
    }
}
