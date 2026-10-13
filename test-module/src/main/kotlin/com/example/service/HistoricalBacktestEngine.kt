package com.example.service

import com.example.viewmodel.AuthViewModel
import com.example.OptionUtils
import com.example.Candle
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.*

data class HistoricalBacktestTradeResult(
    val date: String,
    val symbol: String,
    val optionType: String,
    val entryTime: String,
    val entryPrice: Double,
    val exitTime: String,
    val exitPrice: Double,
    val exitReason: String,
    val pnl: Double,
    val quantity: Int = 65
)

data class OptionChainRow(
    val strike: Int,
    val ceSymbol: String?,
    val cePrice: Double,
    val ceDelta: Double,
    val peSymbol: String?,
    val pePrice: Double,
    val peDelta: Double
)

class HistoricalBacktestEngine(private val viewModel: AuthViewModel) {

    suspend fun runBacktest(appId: String, accessToken: String, startDate: String, endDate: String): List<HistoricalBacktestTradeResult> {
        println("==================================================")
        println("📊 PROVEN TRAILING (POST 1:2 TARGET) BACKTEST")
        println("📅 Period: $startDate to $endDate")
        println("==================================================")

        val results = mutableListOf<HistoricalBacktestTradeResult>()
        var currDate = LocalDate.parse(startDate)
        val finalDate = LocalDate.parse(endDate)

        val indexSymbol = "NSE:NIFTY50-INDEX"
        val quantity = 65 // 1 Nifty lot

        while (!currDate.isAfter(finalDate)) {
            val dayStr = currDate.toString()
            if (currDate.dayOfWeek == java.time.DayOfWeek.SATURDAY || currDate.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
                println("🏖️ [Weekend] Skipping ${currDate.dayOfWeek} ($dayStr) - Markets Closed.")
                currDate = currDate.plusDays(1)
                continue
            }

            println("\n==================================================")
            println("📅 [DATE] Processing Backtest Date: $dayStr")
            println("==================================================")

            try {
                // 1. Fetch Nifty Spot Price
                val spotHistory = viewModel.fetchHistoricalData(
                    appId = appId,
                    accessToken = accessToken,
                    symbol = indexSymbol,
                    resolution = "D",
                    rangeFrom = dayStr,
                    rangeTo = dayStr
                )

                if (spotHistory == null || spotHistory.optString("s") == "no_data" || spotHistory.optJSONArray("candles")?.length() == 0) {
                    println("🏖️ [Holiday / Market Closed] No trading data for $dayStr. Skipping to next day...")
                    currDate = currDate.plusDays(1)
                    continue
                }

                var spotPrice = 23000.0
                if (spotHistory.has("candles")) {
                    val candles = spotHistory.getJSONArray("candles")
                    if (candles.length() > 0) {
                        spotPrice = candles.getJSONArray(0).getDouble(4)
                    }
                }
                println("📊 Nifty Spot Price: %.2f".format(spotPrice))

                val atmStrike = OptionUtils.calculateAtmStrike(spotPrice, 50)
                val candidateStrikes = mutableListOf<Int>()
                for (offset in -10..10) {
                    candidateStrikes.add(atmStrike + (offset * 50))
                }

                var selectedCeSymbol: String? = null
                var selectedPeSymbol: String? = null
                var ce925Price = 0.0
                var pe925Price = 0.0

                var bestCeScore = Double.MAX_VALUE
                var bestPeScore = Double.MAX_VALUE

                val isHistorical = LocalDate.parse(dayStr).isBefore(LocalDate.now().minusDays(7))

                val ceCandlesMap = mutableMapOf<String, List<Candle>>()
                val peCandlesMap = mutableMapOf<String, List<Candle>>()
                val optionChainRows = mutableListOf<OptionChainRow>()

                val timeToExpiryYears = 3.0 / 365.0
                val volatility = 0.18
                val riskFreeRate = 0.065

                for (strike in candidateStrikes) {
                    var strikeCeSymbol: String? = null
                    var strikeCePrice = 0.0
                    var strikeCeDelta = 0.0
                    var strikePeSymbol: String? = null
                    var strikePePrice = 0.0
                    var strikePeDelta = 0.0

                    for (type in listOf("CE", "PE")) {
                        try {
                            if (isHistorical) {
                                val expiredRes = OptionUtils.resolveAndFetchExpiredDerivativeCandles(
                                    viewModel = viewModel,
                                    appId = appId,
                                    token = accessToken,
                                    indexSymbol = indexSymbol,
                                    spotPrice = strike.toDouble(),
                                    optionType = type,
                                    tradeDate = dayStr,
                                    expiryIndex = 0
                                )
                                val symbol = expiredRes.symbol
                                val rawCandles = expiredRes.candles
                                val candles = expandTo5SecondTicks(rawCandles)
                                if (candles.isNotEmpty()) {
                                    if (type == "CE") ceCandlesMap[symbol] = candles else peCandlesMap[symbol] = candles
                                    val candle925 = candles.firstOrNull { it.timestamp.contains("09:25:") } ?: candles.firstOrNull()
                                    if (candle925 != null) {
                                        val priceAt925 = candle925.close
                                        val delta = calculateOptionDelta(spotPrice, strike.toDouble(), timeToExpiryYears, volatility, riskFreeRate, type == "CE")

                                        if (type == "CE") {
                                            strikeCeSymbol = symbol
                                            strikeCePrice = priceAt925
                                            strikeCeDelta = delta
                                        } else {
                                            strikePeSymbol = symbol
                                            strikePePrice = priceAt925
                                            strikePeDelta = delta
                                        }

                                        if (priceAt925 < 180.0) {
                                            val diff = 180.0 - priceAt925
                                            if (type == "CE" && diff < bestCeScore) {
                                                bestCeScore = diff
                                                selectedCeSymbol = symbol
                                                ce925Price = priceAt925
                                            } else if (type == "PE" && diff < bestPeScore) {
                                                bestPeScore = diff
                                                selectedPeSymbol = symbol
                                                pe925Price = priceAt925
                                            }
                                        }
                                    }
                                }
                            } else {
                                val symbol = OptionUtils.resolveWeeklyOptionSymbol(
                                    viewModel = viewModel,
                                    appId = appId,
                                    token = accessToken,
                                    indexSymbol = indexSymbol,
                                    spotPrice = strike.toDouble(),
                                    optionType = type,
                                    expiryIndex = 0
                                )
                                val rawCandles = fetchLowestTimeframeCandles(appId, accessToken, symbol, dayStr)
                                val candles = expandTo5SecondTicks(rawCandles)
                                if (candles.isNotEmpty()) {
                                    if (type == "CE") ceCandlesMap[symbol] = candles else peCandlesMap[symbol] = candles
                                    val candle925 = candles.firstOrNull { it.timestamp.contains("09:25:") } ?: candles.firstOrNull()
                                    if (candle925 != null) {
                                        val priceAt925 = candle925.close
                                        val delta = calculateOptionDelta(spotPrice, strike.toDouble(), timeToExpiryYears, volatility, riskFreeRate, type == "CE")

                                        if (type == "CE") {
                                            strikeCeSymbol = symbol
                                            strikeCePrice = priceAt925
                                            strikeCeDelta = delta
                                        } else {
                                            strikePeSymbol = symbol
                                            strikePePrice = priceAt925
                                            strikePeDelta = delta
                                        }

                                        if (priceAt925 < 180.0) {
                                            val diff = 180.0 - priceAt925
                                            if (type == "CE" && diff < bestCeScore) {
                                                bestCeScore = diff
                                                selectedCeSymbol = symbol
                                                ce925Price = priceAt925
                                            } else if (type == "PE" && diff < bestPeScore) {
                                                bestPeScore = diff
                                                selectedPeSymbol = symbol
                                                pe925Price = priceAt925
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    if (strikeCePrice > 0.0 || strikePePrice > 0.0) {
                        optionChainRows.add(OptionChainRow(strike, strikeCeSymbol, strikeCePrice, strikeCeDelta, strikePeSymbol, strikePePrice, strikePeDelta))
                    }
                }

                printOptionChainTable(spotPrice, optionChainRows, selectedCeSymbol, selectedPeSymbol)

                println("📌 Selected CE: $selectedCeSymbol (9:25 Price: ₹%.2f)".format(ce925Price))
                println("📌 Selected PE: $selectedPeSymbol (9:25 Price: ₹%.2f)".format(pe925Price))

                if (selectedCeSymbol == null || selectedPeSymbol == null) {
                    println("❌ No valid options found trading below ₹180 at 9:25 AM for $dayStr.")
                    currDate = currDate.plusDays(1)
                    continue
                }

                val ceCandles = ceCandlesMap[selectedCeSymbol] ?: emptyList()
                val peCandles = peCandlesMap[selectedPeSymbol] ?: emptyList()

                val ceTickMap = ceCandles.associateBy { it.timestamp }
                val peTickMap = peCandles.associateBy { it.timestamp }
                val allTimestamps = (ceTickMap.keys + peTickMap.keys)
                    .filter { it >= "$dayStr 09:25:00" && it <= "$dayStr 09:30:00" }
                    .sorted()

                println("\n--- [OBSERVATION WINDOW 9:25 AM TO 9:30 AM (5-Second Ticks)] ---")
                var triggeredSymbol: String? = null
                var triggeredType: String? = null
                var entryTime: String? = null
                var entryPrice = 0.0

                var lastSeenCe = ce925Price
                var lastSeenPe = pe925Price

                for (ts in allTimestamps) {
                    val ceCandle = ceTickMap[ts]
                    val peCandle = peTickMap[ts]

                    val currentCe = ceCandle?.close ?: lastSeenCe
                    val currentPe = peCandle?.close ?: lastSeenPe

                    println("⏱️ [$ts] CE ($selectedCeSymbol) Close: ₹%.2f | PE ($selectedPeSymbol) Close: ₹%.2f".format(currentCe, currentPe))

                    if (lastSeenCe < 180.0 && currentCe >= 180.0) {
                        triggeredSymbol = selectedCeSymbol
                        triggeredType = "CE"
                        entryTime = ts
                        entryPrice = currentCe
                        println("🚀 [ENTRY TRIGGERED] CE crossed upward through 180! Entry Price: ₹%.2f at $entryTime".format(entryPrice))
                        break
                    }
                    if (lastSeenPe < 180.0 && currentPe >= 180.0) {
                        triggeredSymbol = selectedPeSymbol
                        triggeredType = "PE"
                        entryTime = ts
                        entryPrice = currentPe
                        println("🚀 [ENTRY TRIGGERED] PE crossed upward through 180! Entry Price: ₹%.2f at $entryTime".format(entryPrice))
                        break
                    }

                    lastSeenCe = currentCe
                    lastSeenPe = currentPe
                }

                if (triggeredSymbol == null || triggeredType == null || entryTime == null) {
                    println("⏸️ [No Trade] Neither contract crossed upward through ₹180 between 9:25 and 9:30 AM.")
                    currDate = currDate.plusDays(1)
                    continue
                }

                // 3. Simulate Trade with Post-1:2 Trailing Stop-Loss
                val tradeCandles = if (triggeredType == "CE") ceCandles else peCandles
                val tradeResult = simulatePost1To2TrailingTrade(triggeredSymbol, triggeredType, entryTime, entryPrice, tradeCandles, dayStr, quantity)
                results.add(tradeResult)

            } catch (e: Exception) {
                println("❌ [Backtest Error] Date $dayStr failed: ${e.message}")
            }

            currDate = currDate.plusDays(1)
        }

        printSummary(results)
        return results
    }

    private fun normalCDF(x: Double): Double {
        val t = 1.0 / (1.0 + 0.2316419 * abs(x))
        val polynomial = t * (0.31938153 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        val cdf = 1.0 - (1.0 / sqrt(2.0 * PI)) * exp(-0.5 * x * x) * polynomial
        return if (x >= 0.0) cdf else 1.0 - cdf
    }

    private fun calculateOptionDelta(spot: Double, strike: Double, timeToExpiryYears: Double, volatility: Double, riskFreeRate: Double, isCall: Boolean): Double {
        if (timeToExpiryYears <= 0.0 || volatility <= 0.0) {
            return if (isCall) if (spot > strike) 1.0 else 0.0 else if (spot < strike) -1.0 else 0.0
        }
        val d1 = (ln(spot / strike) + (riskFreeRate + (volatility * volatility) / 2.0) * timeToExpiryYears) / (volatility * sqrt(timeToExpiryYears))
        val delta = normalCDF(d1)
        return if (isCall) delta else delta - 1.0
    }

    private fun printOptionChainTable(spotPrice: Double, rows: List<OptionChainRow>, selectedCe: String?, selectedPe: String?) {
        println("\n==========================================================================================================")
        println("📊 9:25 AM OPTION CHAIN TABLE (Nifty Spot: ₹%.2f)".format(spotPrice))
        println("==========================================================================================================")
        println(String.format("%-8s | %-30s | %-12s | %-10s | %-30s | %-12s | %-10s",
            "Strike", "CE Symbol", "CE Price (₹)", "CE Delta", "PE Symbol", "PE Price (₹)", "PE Delta"))
        println("----------------------------------------------------------------------------------------------------------")
        for (r in rows.sortedBy { it.strike }) {
            val ceTag = if (r.ceSymbol == selectedCe) " *" else ""
            val peTag = if (r.peSymbol == selectedPe) " *" else ""
            val cePriceStr = if (r.cePrice > 0.0) "₹%.2f".format(r.cePrice) else "-"
            val ceDeltaStr = if (r.cePrice > 0.0) "%.2f".format(r.ceDelta) else "-"
            val pePriceStr = if (r.pePrice > 0.0) "₹%.2f".format(r.pePrice) else "-"
            val peDeltaStr = if (r.pePrice > 0.0) "%.2f".format(r.peDelta) else "-"
            val ceSym = (r.ceSymbol ?: "-") + ceTag
            val peSym = (r.peSymbol ?: "-") + peTag

            println(String.format("%-8d | %-30s | %-12s | %-10s | %-30s | %-12s | %-10s",
                r.strike, ceSym, cePriceStr, ceDeltaStr, peSym, pePriceStr, peDeltaStr))
        }
        println("==========================================================================================================")
        println("(* = Selected strike closest to ₹180 trading below ₹180)")
    }

    private fun expandTo5SecondTicks(candles: List<Candle>): List<Candle> {
        val expanded = mutableListOf<Candle>()
        for (candle in candles) {
            val baseEpoch = candle.epoch
            val open = candle.open
            val high = candle.high
            val low = candle.low
            val close = candle.close

            for (i in 0 until 12) {
                val stepEpoch = baseEpoch + (i * 5)
                val progress = i / 11.0
                val interpolatedPrice = open + (close - open) * progress
                val zdt = java.time.Instant.ofEpochSecond(stepEpoch).atZone(java.time.ZoneId.of("Asia/Kolkata"))
                val timeStr = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                expanded.add(Candle(timestamp = timeStr, open = interpolatedPrice, high = high, low = low, close = interpolatedPrice, volume = candle.volume / 12.0, epoch = stepEpoch))
            }
        }
        return expanded.distinctBy { it.epoch }.sortedBy { it.epoch }
    }

    private suspend fun fetchLowestTimeframeCandles(appId: String, accessToken: String, symbol: String, tradeDate: String): List<Candle> {
        for (resolution in listOf("5S", "10S", "15S", "1")) {
            val history = viewModel.fetchHistoricalData(
                appId = appId,
                accessToken = accessToken,
                symbol = symbol,
                resolution = resolution,
                rangeFrom = tradeDate,
                rangeTo = tradeDate,
                showError = false
            )
            if (history != null && history.optBoolean("success", true) && history.has("candles")) {
                val candlesArr = history.optJSONArray("candles")
                if (candlesArr != null && candlesArr.length() > 0) {
                    val parsed = parseCandles(history)
                    if (parsed.isNotEmpty()) return parsed
                }
            }
        }
        return emptyList()
    }

    private fun parseCandles(json: JSONObject?): List<Candle> {
        val candles = mutableListOf<Candle>()
        if (json == null) return candles
        try {
            val arr = json.optJSONArray("candles") ?: return candles
            for (i in 0 until arr.length()) {
                val item = arr.getJSONArray(i)
                val epoch = item.optLong(0, 0L)
                val open = item.optDouble(1, 0.0)
                val high = item.optDouble(2, 0.0)
                val low = item.optDouble(3, 0.0)
                val close = item.optDouble(4, 0.0)
                val volume = item.optDouble(5, 0.0)

                val zdt = java.time.Instant.ofEpochSecond(epoch).atZone(java.time.ZoneId.of("Asia/Kolkata"))
                val timeStr = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                candles.add(Candle(timestamp = timeStr, open = open, high = high, low = low, close = close, volume = volume, epoch = epoch))
            }
        } catch (_: Exception) {}
        return candles.sortedBy { it.epoch }
    }

    /**
     * TRAILING MECHANISM APPLIED ONLY AFTER 1:2 TARGET (₹220) IS REACHED:
     * - Before hitting 1:2 target (₹220), Stop-Loss remains firmly at ₹160 (initial SL).
     * - Once 1:2 target (₹220) is hit, trailing activates: locks SL at ₹220, and then trails 15 points below the peak high watermark.
     */
    private fun simulatePost1To2TrailingTrade(
        symbol: String,
        optionType: String,
        entryTime: String,
        entryPrice: Double,
        candles: List<Candle>,
        tradeDate: String,
        quantity: Int
    ): HistoricalBacktestTradeResult {
        val tradeCandles = candles.filter { it.timestamp >= entryTime && it.timestamp <= "$tradeDate 15:30:00" }
        if (tradeCandles.isEmpty()) {
            return HistoricalBacktestTradeResult(tradeDate, symbol, optionType, entryTime, entryPrice, entryTime, entryPrice, "NO_CANDLES", 0.0, quantity)
        }

        var stopLoss = 160.0 // Initial SL
        val target1to2 = 220.0 // 1:2 Target
        var highestPrice = entryPrice
        var exitPrice = entryPrice
        var exitTime = entryTime
        var exitReason = "SESSION_END"
        var isExited = false
        var isTrailingActive = false

        println("\n--- [POST 1:2 TARGET TRAILING TRADE (${optionType}: ${symbol})] ---")
        println("🎯 Entry: ₹%.2f at $entryTime | Initial SL: ₹%.2f | 1:2 Target: ₹%.2f | Qty: $quantity".format(entryPrice, stopLoss, target1to2))

        for (candle in tradeCandles) {
            val timePart = candle.timestamp.substring(11)
            val currentHigh = candle.high
            val currentLow = candle.low
            val currentClose = candle.close

            if (currentHigh > highestPrice) {
                highestPrice = currentHigh
            }

            // Trailing mechanism activates ONLY AFTER 1:2 target (₹220) is reached!
            if (!isTrailingActive && highestPrice >= target1to2) {
                isTrailingActive = true
                stopLoss = target1to2 // Move SL to 220 when 1:2 target is hit
                println("🚀 [1:2 TARGET REACHED (₹220)] Trailing activated! SL moved to ₹%.2f".format(stopLoss))
            } else if (isTrailingActive) {
                // Once trailing is active, trail 15 points below peak high watermark
                val newSl = max(target1to2, highestPrice - 15.0)
                if (newSl > stopLoss) {
                    stopLoss = newSl
                    println("📈 [TRAILING SL UPDATED] Peak High: ₹%.2f -> Trailing SL: ₹%.2f".format(highestPrice, stopLoss))
                }
            }

            println("📊 [5S TICK] ${candle.timestamp} | High: ₹%.2f | Low: ₹%.2f | Close: ₹%.2f | Active SL: ₹%.2f | Trailing Active: $isTrailingActive".format(currentHigh, currentLow, currentClose, stopLoss))

            // Stop Loss Hit Check
            if (currentLow <= stopLoss) {
                exitPrice = stopLoss
                exitTime = candle.timestamp
                exitReason = if (isTrailingActive) "TRAILING_SL_HIT" else "INITIAL_SL_HIT"
                isExited = true
                println("🛑 [STOP LOSS HIT] Exited at ₹%.2f at $exitTime ($exitReason: ₹%.2f)".format(exitPrice, stopLoss))
                break
            }

            // Mandatory 9:45 AM Exit if 1:2 target (₹220) was NOT reached yet
            if (timePart >= "09:45:00") {
                if (!isTrailingActive) {
                    exitPrice = currentClose
                    exitTime = candle.timestamp
                    exitReason = "MANDATORY_09:45_EXIT"
                    isExited = true
                    println("⏰ [MANDATORY 9:45 EXIT] Exited at ₹%.2f at $exitTime (1:2 target not reached by 9:45 AM, Close: ₹%.2f)".format(exitPrice, currentClose))
                    break
                } else {
                    println("✨ [9:45 AM BYPASS] 1:2 target already reached and trailing is active. Continuing...".format(currentClose))
                }
            }
        }

        if (!isExited && tradeCandles.isNotEmpty()) {
            val last = tradeCandles.last()
            exitPrice = last.close
            exitTime = last.timestamp
            exitReason = "SESSION_END"
            println("🏁 [SESSION END] Position closed at session end price: ₹%.2f at $exitTime".format(exitPrice))
        }

        val pnl = (exitPrice - entryPrice) * quantity
        println("==================================================")
        println("🏁 TRADE RESULT: $optionType ($symbol)")
        println("Entry: ₹%.2f at $entryTime".format(entryPrice))
        println("Exit:  ₹%.2f at $exitTime ($exitReason)".format(exitPrice))
        println("P&L:   ₹%.2f".format(pnl))
        println("==================================================")

        return HistoricalBacktestTradeResult(tradeDate, symbol, optionType, entryTime, entryPrice, exitTime, exitPrice, exitReason, pnl, quantity)
    }

    private fun printSummary(results: List<HistoricalBacktestTradeResult>) {
        println("\n==================================================")
        println("📈 BACKTEST SUMMARY REPORT")
        println("==================================================")
        println("Total Trades: ${results.size}")
        val totalPnl = results.sumOf { it.pnl }
        val winningTrades = results.count { it.pnl > 0 }
        val losingTrades = results.count { it.pnl <= 0 }
        println("Winning Trades: $winningTrades")
        println("Losing Trades:  $losingTrades")
        println("Total P&L:      ₹%.2f".format(totalPnl))
        println("==================================================")
    }
}
