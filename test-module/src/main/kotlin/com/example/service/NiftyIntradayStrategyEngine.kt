package com.example.service

import com.example.Candle
import com.example.OptionUtils
import com.example.renko.engine.RenkoChartEngine
import com.example.renko.indicator.RenkoIndicators
import com.example.renko.model.*
import com.example.renko.pattern.RenkoPatternDetector
import com.example.viewmodel.AuthViewModel
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

data class NiftyIntradayTradeResult(
    val date: String,
    val optionSymbol: String,
    val optionType: String, // CE or PE
    val strike: Int,
    val entryTime: String,
    val entryPrice: Double,
    val exitTime: String,
    val exitPrice: Double,
    val initialStopLoss: Double,
    val targetPrice: Double,
    val exitReason: String,
    val pnlPoints: Double,
    val pnlRupees: Double,
    val quantity: Int = 65, // 1 Nifty Lot
    val spotSignalReason: String
)

data class NiftyIntradayBacktestSummary(
    val startDate: String,
    val endDate: String,
    val totalDaysProcessed: Int,
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRatePercent: Double,
    val totalPnlPoints: Double,
    val totalPnlRupees: Double,
    val maxDrawdownPoints: Double,
    val maxDrawdownRupees: Double,
    val profitFactor: Double,
    val averageTradePnlRupees: Double,
    val trades: List<NiftyIntradayTradeResult>
)

class NiftyIntradayStrategyEngine(private val viewModel: AuthViewModel) {

    private val indexSymbol = "NSE:NIFTY50-INDEX"
    private val defaultQuantity = 65 // 1 Nifty Lot

    /**
     * Executes the Nifty Dual-Confirmation Renko Intraday Backtest using official Fyers 1-minute historical candles.
     */
    suspend fun runIntradayBacktest(
        appId: String,
        accessToken: String,
        startDate: String,
        endDate: String,
        spotBoxPercent: Double = 0.05, // Recommended 0.05% for Nifty Intraday (Renko.pdf Page 13)
        optionBoxPercent: Double = 3.0, // Recommended 3.0% for Options (Renko.pdf Page 14)
        quantity: Int = defaultQuantity
    ): NiftyIntradayBacktestSummary {
        println("==================================================")
        println("🚀 FYERS NIFTY INTRADAY DUAL-CONFIRMATION STRATEGY")
        println("📅 Period: $startDate to $endDate")
        println("📊 Nifty Spot Renko Box: $spotBoxPercent% | Option Renko Box: $optionBoxPercent%")
        println("==================================================")

        val trades = mutableListOf<NiftyIntradayTradeResult>()
        var currDate = LocalDate.parse(startDate)
        val finalDate = LocalDate.parse(endDate)
        var daysProcessed = 0

        while (!currDate.isAfter(finalDate)) {
            val dayStr = currDate.toString()
            if (currDate.dayOfWeek == java.time.DayOfWeek.SATURDAY || currDate.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
                currDate = currDate.plusDays(1)
                continue
            }

            println("\n==================================================")
            println("📅 [DATE] Processing Nifty Intraday Backtest: $dayStr")
            println("==================================================")

            try {
                // 1. Fetch Nifty Spot 1-Minute Historical Candles from Fyers
                val spotData = viewModel.fetchHistoricalData(
                    appId = appId,
                    accessToken = accessToken,
                    symbol = indexSymbol,
                    resolution = "1",
                    rangeFrom = dayStr,
                    rangeTo = dayStr
                )

                if (spotData == null || spotData.optString("s") == "no_data" || spotData.optJSONArray("candles")?.length() == 0) {
                    println("🏖️ [Holiday / Market Closed] No 1-min data for $dayStr. Skipping...")
                    currDate = currDate.plusDays(1)
                    continue
                }

                daysProcessed++
                val spotCandles = parseCandles(spotData)
                if (spotCandles.size < 20) {
                    println("⚠️ Insufficient 1-min candles (${spotCandles.size}) for $dayStr. Skipping...")
                    currDate = currDate.plusDays(1)
                    continue
                }

                // 2. Build Nifty Spot Renko Bricks (0.05% Box Size)
                val spotRenkoEngine = RenkoChartEngine(renkoType = RenkoType.PERCENTAGE, value = spotBoxPercent)
                val spotBricks = spotRenkoEngine.buildBricks(spotCandles)
                println("🧱 Spot Renko Bricks Formed: ${spotBricks.size}")

                if (spotBricks.size < 10) {
                    currDate = currDate.plusDays(1)
                    continue
                }

                // 3. Compute Spot Trend Filters (20 EMA & 40 EMA on Spot Renko)
                val spotEma20 = RenkoIndicators.calculateEMA(spotBricks, 20)
                val spotEma40 = RenkoIndicators.calculateEMA(spotBricks, 40)
                val spotPatterns = RenkoPatternDetector.detectPatterns(spotBricks)

                // Spot Price at market open (9:15 AM)
                val openSpotPrice = spotCandles.first().open
                val atmStrike = OptionUtils.calculateAtmStrike(openSpotPrice, 50)
                println("📊 Nifty Opening Spot: %.2f | ATM Strike: $atmStrike".format(openSpotPrice))

                val isHistoricalExpired = currDate.isBefore(LocalDate.now().minusDays(7))

                // Track active intraday position for today
                var isPositionOpen = false

                for (pattern in spotPatterns) {
                    if (isPositionOpen) continue // Strictly 1 trade at a time intraday

                    val idx = pattern.brickIndex
                    val brick = spotBricks[idx]

                    // Time filter: Allow trade entries from market open (09:15 AM) until 02:30 PM IST
                    val timeStr = brick.timestamp.takeLast(8)
                    val localTime = try { LocalTime.parse(timeStr) } catch (_: Exception) { LocalTime.of(10, 0) }
                    if (localTime.isBefore(LocalTime.of(9, 15)) || localTime.isAfter(LocalTime.of(14, 30))) {
                        continue
                    }

                    val e20 = spotEma20[idx] ?: continue
                    val e40 = spotEma40[idx] ?: continue

                    var signalOptionType: String? = null
                    var signalReason = ""

                    // Bullish Spot Trigger: 20 EMA > 40 EMA + Bullish Pattern
                    if (e20 > e40 && (
                                pattern.patternType == RenkoPatternType.BULLISH_SWING_BREAKOUT ||
                                        pattern.patternType == RenkoPatternType.ONE_BACK_BULLISH ||
                                        pattern.patternType == RenkoPatternType.TWO_BACK_BULLISH
                                )) {
                        signalOptionType = "CE"
                        signalReason = "Spot Bullish: 20 EMA > 40 EMA + ${pattern.patternType}"
                    }
                    // Bearish Spot Trigger: 20 EMA < 40 EMA + Bearish Pattern
                    else if (e20 < e40 && (
                                pattern.patternType == RenkoPatternType.BEARISH_SWING_BREAKDOWN ||
                                        pattern.patternType == RenkoPatternType.ONE_BACK_BEARISH ||
                                        pattern.patternType == RenkoPatternType.TWO_BACK_BEARISH
                                )) {
                        signalOptionType = "PE"
                        signalReason = "Spot Bearish: 20 EMA < 40 EMA + ${pattern.patternType}"
                    }

                    if (signalOptionType != null) {
                        // 4. Resolve Option Symbol & Fetch Option Candles via Fyers API
                        val strikeToTrade = atmStrike
                        val optionSymbol = if (isHistoricalExpired) {
                            try {
                                OptionUtils.resolveAndFetchExpiredDerivativeCandles(
                                    viewModel = viewModel,
                                    appId = appId,
                                    token = accessToken,
                                    indexSymbol = indexSymbol,
                                    spotPrice = strikeToTrade.toDouble(),
                                    optionType = signalOptionType,
                                    tradeDate = dayStr,
                                    expiryIndex = 0
                                ).symbol
                            } catch (_: Exception) { null }
                        } else {
                            try {
                                OptionUtils.resolveWeeklyOptionSymbol(
                                    viewModel = viewModel,
                                    appId = appId,
                                    token = accessToken,
                                    indexSymbol = indexSymbol,
                                    spotPrice = strikeToTrade.toDouble(),
                                    optionType = signalOptionType,
                                    expiryIndex = 0
                                )
                            } catch (_: Exception) { null }
                        }

                        if (optionSymbol == null) {
                            println("⚠️ Could not resolve option symbol for $signalOptionType at strike $strikeToTrade")
                            continue
                        }

                        val optCandlesData = if (isHistoricalExpired) {
                            try {
                                OptionUtils.resolveAndFetchExpiredDerivativeCandles(
                                    viewModel = viewModel,
                                    appId = appId,
                                    token = accessToken,
                                    indexSymbol = indexSymbol,
                                    spotPrice = strikeToTrade.toDouble(),
                                    optionType = signalOptionType,
                                    tradeDate = dayStr,
                                    expiryIndex = 0
                                ).candles
                            } catch (_: Exception) { emptyList() }
                        } else {
                            val optHist = viewModel.fetchHistoricalData(
                                appId = appId,
                                accessToken = accessToken,
                                symbol = optionSymbol,
                                resolution = "1",
                                rangeFrom = dayStr,
                                rangeTo = dayStr
                            )
                            if (optHist != null) parseCandles(optHist) else emptyList()
                        }

                        if (optCandlesData.size < 10) {
                            println("⚠️ Insufficient option candles for symbol $optionSymbol")
                            continue
                        }

                        // 5. Build Option Renko Bricks (3.0% Box Size)
                        val optRenkoEngine = RenkoChartEngine(renkoType = RenkoType.PERCENTAGE, value = optionBoxPercent)
                        val optBricks = optRenkoEngine.buildBricks(optCandlesData)
                        if (optBricks.size < 5) continue

                        // Find entry brick corresponding to signal time
                        val entryOptBrick = optBricks.firstOrNull { it.timestamp >= brick.timestamp } ?: optBricks.last()
                        val entryPrice = entryOptBrick.close
                        val brickSize = entryOptBrick.brickSize
                        val initialSl = entryPrice - (1.5 * brickSize) // Initial SL 1.5 bricks below entry
                        val targetPrice = entryPrice + (3.0 * brickSize) // Target 1:2 R:R (3 bricks gain)

                        println("🎯 [INTRADAY SIGNAL ENTRY] $signalOptionType ($optionSymbol) | Entry: ₹%.2f | SL: ₹%.2f | Target: ₹%.2f".format(entryPrice, initialSl, targetPrice))

                        // 6. Simulate Trade Execution & Management until Target / SL / 03:15 PM Exit
                        isPositionOpen = true
                        var exitPrice = entryPrice
                        var exitTime = entryOptBrick.timestamp
                        var exitReason = "Intraday Square-off (3:15 PM)"
                        var currentSl = initialSl
                        var highestPrice = entryPrice

                        for (optBrick in optBricks) {
                            if (optBrick.timestamp <= entryOptBrick.timestamp) continue

                            val tTime = try { LocalTime.parse(optBrick.timestamp.takeLast(8)) } catch (_: Exception) { LocalTime.of(15, 0) }

                            if (optBrick.high > highestPrice) {
                                highestPrice = optBrick.high
                            }

                            // Trailing SL: Lock in profits after 1:1 gain reached
                            if (highestPrice >= entryPrice + (1.5 * brickSize)) {
                                val trailedSl = max(entryPrice, highestPrice - (1.5 * brickSize))
                                if (trailedSl > currentSl) {
                                    currentSl = trailedSl
                                }
                            }

                            // Stop Loss Check
                            if (optBrick.low <= currentSl) {
                                exitPrice = currentSl
                                exitTime = optBrick.timestamp
                                exitReason = if (currentSl > initialSl) "Trailing SL Hit" else "Initial SL Hit"
                                break
                            }

                            // Target Check
                            if (optBrick.high >= targetPrice) {
                                exitPrice = targetPrice
                                exitTime = optBrick.timestamp
                                exitReason = "Target Reached (1:2 R:R)"
                                break
                            }

                            // Mandatory Intraday Close at 3:15 PM
                            if (tTime.isAfter(LocalTime.of(15, 15)) || tTime == LocalTime.of(15, 15)) {
                                exitPrice = optBrick.close
                                exitTime = optBrick.timestamp
                                exitReason = "Mandatory Intraday Square-off (3:15 PM)"
                                break
                            }
                        }

                        val pnlPts = exitPrice - entryPrice
                        val pnlRs = pnlPts * quantity

                        trades.add(
                            NiftyIntradayTradeResult(
                                date = dayStr,
                                optionSymbol = optionSymbol,
                                optionType = signalOptionType,
                                strike = strikeToTrade,
                                entryTime = entryOptBrick.timestamp,
                                entryPrice = entryPrice,
                                exitTime = exitTime,
                                exitPrice = exitPrice,
                                initialStopLoss = initialSl,
                                targetPrice = targetPrice,
                                exitReason = exitReason,
                                pnlPoints = pnlPts,
                                pnlRupees = pnlRs,
                                quantity = quantity,
                                spotSignalReason = signalReason
                            )
                        )

                        println("🏁 [TRADE CLOSED] P&L: %.2f Pts (₹%.2f) | Exit Reason: $exitReason".format(pnlPts, pnlRs))
                    }
                }
            } catch (e: Exception) {
                println("❌ Error processing $dayStr: ${e.message}")
            }

            currDate = currDate.plusDays(1)
        }

        val totalTrades = trades.size
        val winningCount = trades.count { it.pnlPoints > 0 }
        val losingCount = trades.count { it.pnlPoints < 0 }
        val winRate = if (totalTrades > 0) (winningCount.toDouble() / totalTrades) * 100.0 else 0.0

        val totalPnlPts = trades.sumOf { it.pnlPoints }
        val totalPnlRs = trades.sumOf { it.pnlRupees }

        var peakPnl = 0.0
        var maxDdPts = 0.0

        var cumPnl = 0.0
        for (trade in trades) {
            cumPnl += trade.pnlPoints
            if (cumPnl > peakPnl) {
                peakPnl = cumPnl
            }
            val dd = peakPnl - cumPnl
            if (dd > maxDdPts) {
                maxDdPts = dd
            }
        }

        val maxDdRs = maxDdPts * quantity
        val totalWinsAmount = trades.filter { it.pnlRupees > 0 }.sumOf { it.pnlRupees }
        val totalLossesAmount = kotlin.math.abs(trades.filter { it.pnlRupees < 0 }.sumOf { it.pnlRupees })
        val profitFactor = if (totalLossesAmount > 0) totalWinsAmount / totalLossesAmount else if (totalWinsAmount > 0) 99.0 else 0.0
        val avgPnlRs = if (totalTrades > 0) totalPnlRs / totalTrades else 0.0

        val summary = NiftyIntradayBacktestSummary(
            startDate = startDate,
            endDate = endDate,
            totalDaysProcessed = daysProcessed,
            totalTrades = totalTrades,
            winningTrades = winningCount,
            losingTrades = losingCount,
            winRatePercent = winRate,
            totalPnlPoints = totalPnlPts,
            totalPnlRupees = totalPnlRs,
            maxDrawdownPoints = maxDdPts,
            maxDrawdownRupees = maxDdRs,
            profitFactor = profitFactor,
            averageTradePnlRupees = avgPnlRs,
            trades = trades
        )

        printSummaryReport(summary)
        return summary
    }

    private fun parseCandles(jsonObj: JSONObject): List<Candle> {
        val candles = mutableListOf<Candle>()
        if (!jsonObj.has("candles")) return candles

        val arr = jsonObj.getJSONArray("candles")
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

            candles.add(
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
        return candles
    }

    private fun printSummaryReport(summary: NiftyIntradayBacktestSummary) {
        println("\n==================================================")
        println("📊 FYERS NIFTY INTRADAY STRATEGY BACKTEST REPORT")
        println("==================================================")
        println("Period: ${summary.startDate} to ${summary.endDate} (${summary.totalDaysProcessed} Trading Days)")
        println("Total Trades: ${summary.totalTrades} | Wins: ${summary.winningTrades} | Losses: ${summary.losingTrades}")
        println("Win Rate: %.2f%%".format(summary.winRatePercent))
        println("Total P&L: %.2f Points | ₹%.2f".format(summary.totalPnlPoints, summary.totalPnlRupees))
        println("Max Drawdown: %.2f Points | ₹%.2f".format(summary.maxDrawdownPoints, summary.maxDrawdownRupees))
        println("Profit Factor: %.2f".format(summary.profitFactor))
        println("Avg Trade P&L: ₹%.2f".format(summary.averageTradePnlRupees))
        println("==================================================\n")
    }
}
