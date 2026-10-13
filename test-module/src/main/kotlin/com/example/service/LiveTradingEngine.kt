package com.example.service

import com.example.viewmodel.AuthViewModel
import com.example.OptionUtils
import okhttp3.*
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.max

class LiveTradingEngine(private val viewModel: AuthViewModel) {

    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Executes the live 9:25 AM CE/PE ₹180 Breakout Strategy using 100% real-time WebSocket data.
     */
    suspend fun executeLiveStrategy(appId: String, accessToken: String) {
        val today = LocalDate.now().toString()
        println("==================================================")
        println("🔴 [100% WEBSOCKET LIVE] 9:25 AM CE/PE ₹180 BREAKOUT STRATEGY - $today")
        println("==================================================")

        val indexSymbol = "NSE:NIFTY50-INDEX"
        val quantity = 65 // 1 Nifty lot

        // 1. Fetch Nifty Spot Price to anchor ATM strike
        val spotHistory = viewModel.fetchHistoricalData(
            appId = appId,
            accessToken = accessToken,
            symbol = indexSymbol,
            resolution = "D",
            rangeFrom = today,
            rangeTo = today,
            showError = false
        )

        var spotPrice = 23000.0
        if (spotHistory != null && spotHistory.has("candles")) {
            val candles = spotHistory.getJSONArray("candles")
            if (candles.length() > 0) {
                spotPrice = candles.getJSONArray(0).getDouble(4)
            }
        }
        println("📊 Nifty Spot Price: $spotPrice")

        val atmStrike = OptionUtils.calculateAtmStrike(spotPrice, 50)
        println("🎯 Calculated ATM Strike: $atmStrike")

        // 2. Resolve Candidate Symbols for ATM ± 10 strikes (CE and PE)
        val candidateStrikes = mutableListOf<Int>()
        for (offset in -10..10) {
            candidateStrikes.add(atmStrike + (offset * 50))
        }

        val allCandidateSymbols = mutableListOf<String>()
        val symbolToStrikeMap = mutableMapOf<String, Pair<Int, String>>() // symbol -> (strike, type)

        for (strike in candidateStrikes) {
            for (type in listOf("CE", "PE")) {
                try {
                    val symbol = OptionUtils.resolveWeeklyOptionSymbol(
                        viewModel = viewModel,
                        appId = appId,
                        token = accessToken,
                        indexSymbol = indexSymbol,
                        spotPrice = strike.toDouble(),
                        optionType = type,
                        expiryIndex = 0
                    )
                    allCandidateSymbols.add(symbol)
                    symbolToStrikeMap[symbol] = Pair(strike, type)
                } catch (_: Exception) {}
            }
        }

        println("🔌 [WebSocket] Connecting to Fyers Data Socket v3 to capture live 9:25 AM option prices...")
        scope.launch {
            TelegramNotifier.sendAlert("🟢 [Fyers Live] Connecting to WebSocket for 100% real-time strike scanning & tick execution...")
        }

        // 3. Connect to Fyers WebSocket to receive live ticks for 9:25 AM snapshot & breakout monitoring
        val wsUrl = "wss://socket.fyers.in/v3/data/en?access_token=$appId:$accessToken"
        val request = Request.Builder().url(wsUrl).build()

        val symbolPricesAt925 = mutableMapOf<String, Double>()
        val snapshotDeferred = CompletableDeferred<Boolean>()

        val liveTickListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("🟢 [WebSocket] Connected successfully. Subscribing to ${allCandidateSymbols.size} candidate option symbols.")
                val subMsg = JSONObject().apply {
                    put("symbol", allCandidateSymbols.joinToString(","))
                    put("data_type", "symbolUpdate")
                }
                webSocket.send(subMsg.toString())
            }

            var selectedCeSymbol: String? = null
            var selectedPeSymbol: String? = null
            var ce925Price = 0.0
            var pe925Price = 0.0
            var breakoutSignal: LiveTradeSignal? = null
            val lastPrices = mutableMapOf<String, Double>()
            var isSnapshotCaptured = false

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val symbol = json.optString("symbol", json.optString("s", ""))
                    val ltp = json.optDouble("ltp", json.optDouble("lp", 0.0))

                    if (symbol.isNotEmpty() && ltp > 0.0) {
                        val nowTime = LocalTime.now()

                        // Phase 1: Capture 9:25 AM Snapshot from live WebSocket ticks
                        if (!isSnapshotCaptured && nowTime.isAfter(LocalTime.of(9, 25, 0)) && nowTime.isBefore(LocalTime.of(9, 25, 15))) {
                            symbolPricesAt925[symbol] = ltp
                            lastPrices[symbol] = ltp
                        } else if (!isSnapshotCaptured && nowTime.isAfter(LocalTime.of(9, 25, 15)) && symbolPricesAt925.isNotEmpty()) {
                            isSnapshotCaptured = true
                            // Select CE and PE trading below 180 closest to 180
                            var bestCeScore = Double.MAX_VALUE
                            var bestPeScore = Double.MAX_VALUE

                            for ((sym, price) in symbolPricesAt925) {
                                val info = symbolToStrikeMap[sym] ?: continue
                                if (price < 180.0) {
                                    val diff = 180.0 - price
                                    if (info.second == "CE" && diff < bestCeScore) {
                                        bestCeScore = diff
                                        selectedCeSymbol = sym
                                        ce925Price = price
                                    } else if (info.second == "PE" && diff < bestPeScore) {
                                        bestPeScore = diff
                                        selectedPeSymbol = sym
                                        pe925Price = price
                                    }
                                }
                            }

                            println("📌 [WebSocket Snapshot] Selected Live CE: $selectedCeSymbol @ ₹$ce925Price | Selected Live PE: $selectedPeSymbol @ ₹$pe925Price")
                            
                            if (selectedCeSymbol != null && selectedPeSymbol != null) {
                                scope.launch {
                                    TelegramNotifier.sendAlert("🎯 [9:25 AM Trade Started - 100% WebSocket] Watchlisted Strikes:\n🟢 CE: $selectedCeSymbol @ ₹%.2f\n🔴 PE: $selectedPeSymbol @ ₹%.2f".format(ce925Price, pe925Price))
                                }
                                // Re-subscribe strictly to selected CE and PE for low latency
                                val focusSub = JSONObject().apply {
                                    put("symbol", listOf(selectedCeSymbol, selectedPeSymbol).joinToString(","))
                                    put("data_type", "symbolUpdate")
                                }
                                webSocket.send(focusSub.toString())
                            }
                            snapshotDeferred.complete(true)
                        }

                        // Phase 2: Observation & Breakout Window (9:25:15 to 9:30:00)
                        if (isSnapshotCaptured && selectedCeSymbol != null && selectedPeSymbol != null && breakoutSignal == null) {
                            val observationEnd = LocalTime.of(9, 30, 0)
                            if (nowTime.isBefore(observationEnd)) {
                                val prevPrice = lastPrices[symbol] ?: ltp
                                if (symbol == selectedCeSymbol) {
                                    if (prevPrice < 180.0 && ltp >= 180.0) {
                                        breakoutSignal = LiveTradeSignal(symbol, "CE", ltp, nowTime.toString())
                                        println("🚀 [100% WS BREAKOUT] CE ($symbol) crossed upward through 180 at $nowTime with LTP $ltp")
                                        webSocket.cancel()
                                        return
                                    }
                                    lastPrices[symbol] = ltp
                                } else if (symbol == selectedPeSymbol) {
                                    if (prevPrice < 180.0 && ltp >= 180.0) {
                                        breakoutSignal = LiveTradeSignal(symbol, "PE", ltp, nowTime.toString())
                                        println("🚀 [100% WS BREAKOUT] PE ($symbol) crossed upward through 180 at $nowTime with LTP $ltp")
                                        webSocket.cancel()
                                        return
                                    }
                                    lastPrices[symbol] = ltp
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️ [WebSocket Message Error]: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("❌ [WebSocket Failure]: ${t.message}")
                snapshotDeferred.complete(false)
            }
        }

        val ws = client.newWebSocket(request, liveTickListener)

        // Wait for snapshot capture
        try {
            kotlinx.coroutines.withTimeout(30 * 1000L) {
                snapshotDeferred.await()
            }
        } catch (_: Exception) {}

        // Wait until 9:30 AM for breakout signal
        try {
            kotlinx.coroutines.withTimeout(5 * 60 * 1000L) {
                while (LocalTime.now().isBefore(LocalTime.of(9, 30, 0))) {
                    kotlinx.coroutines.delay(500L)
                }
            }
        } catch (_: Exception) {}

        ws.close(1000, "Observation session finished")

        // If no trade triggered by 9:30 AM
        val activeSignal = liveTickListener.breakoutSignal
        if (activeSignal == null) {
            println("⏸️ [No Trade] Neither CE nor PE crossed upward through ₹180 by 9:30 AM.")
            scope.launch {
                TelegramNotifier.sendAlert("⏸️ [9:30 AM No Entry] Neither CE nor PE crossed upward through ₹180 between 9:25 and 9:30 AM. No trade today.")
            }
            return
        }

        // 4. Live Position Management via WebSocket Data Only
        executeLivePositionManagement(appId, accessToken, activeSignal, quantity)
    }

    private suspend fun executeLivePositionManagement(appId: String, accessToken: String, signal: LiveTradeSignal, quantity: Int) {
        println("🎯 [100% WS Position Active] ${signal.optionType} (${signal.symbol}) | Entry: ${signal.entryPrice} at ${signal.entryTime}")
        
        scope.launch {
            TelegramNotifier.sendAlert("🚀 [Entry Taken - 100% WS] Entered ${signal.optionType} (${signal.symbol}) at ₹%.2f | Initial SL: ₹160 | Target: ₹220".format(signal.entryPrice))
        }

        var stopLoss = 160.0 // Initial SL
        var highestPrice = signal.entryPrice
        var isPositionOpen = true
        var isTrailingActive = false

        val wsUrl = "wss://socket.fyers.in/v3/data/en?access_token=$appId:$accessToken"
        val request = Request.Builder().url(wsUrl).build()

        val managementListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val subMsg = JSONObject().apply {
                    put("symbol", signal.symbol)
                    put("data_type", "symbolUpdate")
                }
                webSocket.send(subMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isPositionOpen) return
                try {
                    val json = JSONObject(text)
                    val symbol = json.optString("symbol", json.optString("s", ""))
                    val ltp = json.optDouble("ltp", json.optDouble("lp", 0.0))

                    if (symbol == signal.symbol && ltp > 0.0) {
                        val nowTime = LocalTime.now()

                        if (ltp > highestPrice) {
                            highestPrice = ltp
                        }

                        // Post 1:2 Target Trailing Mechanism (100% WebSocket LTP)
                        if (!isTrailingActive && highestPrice >= 220.0) {
                            isTrailingActive = true
                            stopLoss = 220.0
                            println("🚀 [Live 1:2 Target Reached (₹220)] Trailing activated! SL moved to ₹220.00")
                            scope.launch {
                                TelegramNotifier.sendAlert("🚀 [Fyers Live] 1:2 Target Reached! Trailing active, SL locked at ₹220")
                            }
                        } else if (isTrailingActive) {
                            val newSl = max(220.0, highestPrice - 15.0)
                            if (newSl > stopLoss) {
                                stopLoss = newSl
                                println("📈 [Live Trailing SL Updated] Peak High: ₹$highestPrice -> Trailing SL: ₹$stopLoss")
                            }
                        }

                        // Stop Loss Hit Check
                        if (ltp <= stopLoss) {
                            isPositionOpen = false
                            val exitReason = if (isTrailingActive) "Trailing SL Hit" else "Initial Stop Loss Hit"
                            val pnl = (stopLoss - signal.entryPrice) * quantity
                            println("🛑 [LIVE WS SL HIT] Exit at $stopLoss | P&L: ₹%.2f".format(pnl))
                            scope.launch {
                                TelegramNotifier.sendAlert("🏁 [Trade Exit - $exitReason] Exited ${signal.optionType} (${signal.symbol}) at ₹$stopLoss | P&L: ₹%.2f".format(pnl))
                            }
                            webSocket.cancel()
                            return
                        }

                        // Mandatory Exit at 9:45 AM if 1:2 target (₹220) was NOT reached yet
                        val mandatoryExitTime = LocalTime.of(9, 45, 0)
                        if (nowTime.isAfter(mandatoryExitTime) || nowTime == mandatoryExitTime) {
                            if (!isTrailingActive) {
                                isPositionOpen = false
                                val pnl = (ltp - signal.entryPrice) * quantity
                                println("⏰ [Mandatory Exit 9:45 AM] Exit at LTP $ltp | P&L: ₹%.2f".format(pnl))
                                scope.launch {
                                    TelegramNotifier.sendAlert("🏁 [Trade Exit - Mandatory 9:45 AM Exit] Exited ${signal.optionType} (${signal.symbol}) at ₹$ltp | P&L: ₹%.2f".format(pnl))
                                }
                                webSocket.cancel()
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️ [Management WebSocket Error]: ${e.message}")
                }
            }
        }

        val managementWs = client.newWebSocket(request, managementListener)

        while (isPositionOpen) {
            kotlinx.coroutines.delay(1000L)
            if (LocalTime.now().isAfter(LocalTime.of(15, 30, 0))) {
                isPositionOpen = false
                managementWs.cancel()
                val finalLtp = highestPrice
                val pnl = (finalLtp - signal.entryPrice) * quantity
                println("⏰ [Market Close] Position closed at session end.")
                scope.launch {
                    TelegramNotifier.sendAlert("🏁 [Trade Exit - Session End] Position closed at session end | P&L: ₹%.2f".format(pnl))
                }
                break
            }
        }
    }

    private data class LiveTradeSignal(
        val symbol: String,
        val optionType: String,
        val entryPrice: Double,
        val entryTime: String
    )
}
