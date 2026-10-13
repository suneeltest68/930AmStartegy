package com.example.renko.service

import com.example.Candle
import com.example.renko.engine.RenkoChartEngine
import com.example.renko.model.*
import com.example.renko.strategy.RenkoStrategies
import com.example.viewmodel.AuthViewModel
import java.time.LocalDate

data class RenkoBacktestResult(
    val strategyName: String,
    val symbol: String,
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRatePercent: Double,
    val totalProfitPoints: Double,
    val totalProfitAmount: Double,
    val maxDrawdownPoints: Double,
    val trades: List<RenkoTradeDetail>
)

data class RenkoTradeDetail(
    val entryTimestamp: String,
    val exitTimestamp: String,
    val action: TradeAction,
    val entryPrice: Double,
    val exitPrice: Double,
    val stopLoss: Double,
    val target: Double?,
    val pnlPoints: Double,
    val pnlAmount: Double,
    val exitReason: String
)

class RenkoBacktestEngine(private val viewModel: AuthViewModel) {

    /**
     * Runs a comprehensive Renko Backtest on 1-min candle historical data using the specified strategy.
     */
    suspend fun runRenkoBacktest(
        appId: String,
        accessToken: String,
        symbol: String,
        startDate: String,
        endDate: String,
        strategyChoice: Int = 1,
        renkoType: RenkoType = RenkoType.PERCENTAGE,
        boxValue: Double = 0.05, // Recommended 0.05% for Nifty intraday, 3% for Options
        quantity: Int = 65
    ): RenkoBacktestResult {
        println("==================================================")
        println("🧱 RENKO CHARTS STRATEGY BACKTEST (Renko.pdf)")
        println("📌 Symbol: $symbol | Period: $startDate to $endDate")
        println("📌 Box Size: $boxValue% ($renkoType) | Strategy Choice: $strategyChoice")
        println("==================================================")

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
                resolution = "1",
                rangeFrom = dayStr,
                rangeTo = dayStr
            )

            if (histData != null && histData.has("candles")) {
                val candlesArray = histData.getJSONArray("candles")
                for (i in 0 until candlesArray.length()) {
                    val arr = candlesArray.getJSONArray(i)
                    val epoch = arr.getLong(0)
                    val open = arr.getDouble(1)
                    val high = arr.getDouble(2)
                    val low = arr.getDouble(3)
                    val close = arr.getDouble(4)
                    val volume = arr.getDouble(5)
                    val timestamp = java.time.Instant.ofEpochSecond(epoch)
                        .atZone(java.time.ZoneId.of("Asia/Kolkata"))
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
            println("❌ No historical candles retrieved for backtest.")
            return RenkoBacktestResult(
                strategyName = "Strategy $strategyChoice",
                symbol = symbol,
                totalTrades = 0,
                winningTrades = 0,
                losingTrades = 0,
                winRatePercent = 0.0,
                totalProfitPoints = 0.0,
                totalProfitAmount = 0.0,
                maxDrawdownPoints = 0.0,
                trades = emptyList()
            )
        }

        println("📊 Total 1-min Candles Retrieved: ${allCandles.size}")

        // Build Renko Bricks
        val renkoEngine = RenkoChartEngine(renkoType = renkoType, value = boxValue)
        val bricks = renkoEngine.buildBricks(allCandles)
        println("🧱 Total Renko Bricks Constructed: ${bricks.size}")

        if (bricks.size < 10) {
            println("⚠️ Not enough Renko bricks formed for strategy evaluation.")
            return RenkoBacktestResult(
                strategyName = "Strategy $strategyChoice",
                symbol = symbol,
                totalTrades = 0,
                winningTrades = 0,
                losingTrades = 0,
                winRatePercent = 0.0,
                totalProfitPoints = 0.0,
                totalProfitAmount = 0.0,
                maxDrawdownPoints = 0.0,
                trades = emptyList()
            )
        }

        // Generate Signals
        val signals = when (strategyChoice) {
            1 -> RenkoStrategies.evaluateStrategy1DmaIntraday(symbol, bricks)
            3 -> RenkoStrategies.evaluateStrategy3SuperTrend(symbol, bricks)
            7 -> RenkoStrategies.evaluateStrategy7MultiSwing(symbol, bricks)
            9 -> RenkoStrategies.evaluateStrategy9TmaRsi(symbol, bricks)
            11 -> RenkoStrategies.evaluateStrategy11OptionBuyingArsi(symbol, bricks)
            12 -> RenkoStrategies.evaluateStrategy12OptionSellingArsi(symbol, bricks)
            13 -> RenkoStrategies.evaluateStrategy13EtfBuying(symbol, bricks)
            else -> RenkoStrategies.evaluateStrategy1DmaIntraday(symbol, bricks)
        }

        println("🎯 Total Signals Generated: ${signals.size}")

        // Simulate Trades
        val trades = mutableListOf<RenkoTradeDetail>()
        var activeSignal: RenkoTradeSignal? = null
        var peakPnL = 0.0
        var maxDrawdown = 0.0

        for (brick in bricks) {
            if (activeSignal == null) {
                // Find signal at current brick timestamp
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
                    if (cumulativePnL > peakPnL) {
                        peakPnL = cumulativePnL
                    }
                    val currentDd = peakPnL - cumulativePnL
                    if (currentDd > maxDrawdown) {
                        maxDrawdown = currentDd
                    }

                    activeSignal = null
                }
            }
        }

        val winningCount = trades.count { it.pnlPoints > 0 }
        val losingCount = trades.count { it.pnlPoints < 0 }
        val winRate = if (trades.isNotEmpty()) (winningCount.toDouble() / trades.size) * 100.0 else 0.0
        val totalPoints = trades.sumOf { it.pnlPoints }
        val totalAmount = trades.sumOf { it.pnlAmount }

        val summary = RenkoBacktestResult(
            strategyName = signals.firstOrNull()?.strategyName ?: "Renko Strategy $strategyChoice",
            symbol = symbol,
            totalTrades = trades.size,
            winningTrades = winningCount,
            losingTrades = losingCount,
            winRatePercent = winRate,
            totalProfitPoints = totalPoints,
            totalProfitAmount = totalAmount,
            maxDrawdownPoints = maxDrawdown,
            trades = trades
        )

        println("\n==================================================")
        println("📈 RENKO BACKTEST SUMMARY")
        println("==================================================")
        println("Strategy: ${summary.strategyName}")
        println("Total Trades: ${summary.totalTrades} | Wins: ${summary.winningTrades} | Losses: ${summary.losingTrades}")
        println("Win Rate: %.2f%%".format(summary.winRatePercent))
        println("Total Profit (Points): %.2f | Total Profit (₹): %.2f".format(summary.totalProfitPoints, summary.totalProfitAmount))
        println("Max Drawdown (Points): %.2f".format(summary.maxDrawdownPoints))
        println("==================================================\n")

        return summary
    }
}
