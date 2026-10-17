package com.example.service

import com.example.viewmodel.AuthViewModel
import com.example.OptionUtils
import com.tts.`in`.websocket.FyersSocket
import com.tts.`in`.websocket.FyersSocketDelegate
import `in`.tts.hsjavalib.ChannelModes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.Duration
import kotlin.math.max

class LiveTradingEngine(private val viewModel: AuthViewModel) {

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Executes the live 9:25 AM CE/PE ₹180 Breakout Strategy using the official Fyers Java SDK FyersSocket
     * with clock-drift immune tick snapshot collection and auto-retry protection.
     */
    suspend fun executeLiveStrategy(appId: String, accessToken: String) {
        val istZone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.now(istZone)
        val target925 = now.withHour(9).withMinute(25).withSecond(0).withNano(0)
        val marketCloseToday = now.withHour(15).withMinute(25).withSecond(0).withNano(0)

        if (now.isAfter(marketCloseToday)) {
            println("🏖️ [Fyers Live] Market already closed for today ($now). Skipping strategy execution until tomorrow.")
            TelegramNotifier.sendAlert("🏖️ [Fyers Live] Market already closed for today. Daemon will wait for tomorrow's session.")
            return
        }

        if (now.isBefore(target925)) {
            val waitMillis = Duration.between(now, target925).toMillis()
            println("⏳ [Fyers Live] Current time is ${now.toLocalTime()}. Waiting until 9:25 AM IST (${waitMillis / 1000 / 60} minutes) to start trading session...")
            TelegramNotifier.sendAlert("⏳ [Fyers Live] Authenticated & connected. Waiting until 9:25 AM IST to start trading session...")
            delay(waitMillis)
        } else {
            println("🚀 [Fyers Live] Starting strategy execution immediately at ${now.toLocalTime()}...")
        }

        val today = LocalDate.now().toString()
        println("==================================================")
        println("🔴 [STRICT LIVE TRADING] 9:25 AM STRATEGY - $today")
        println("==================================================")

        val indexSymbol = "NSE:NIFTY50-INDEX"
        val quantity = 65 // 1 Nifty lot

        // 1. Fetch Nifty Spot Price at startup with strict retry-with-backoff and robust fallbacks (1-min today, daily today, or previous trading day)
        val spotPrice = try {
            retryWithBackoff(maxRetries = 3) {
                var fetchedPrice: Double? = null

                // Try 1: Fetch 1-min candles for today (available from 9:15 AM onwards)
                val res1min = viewModel.fetchHistoricalData(
                    appId = appId,
                    accessToken = accessToken,
                    symbol = indexSymbol,
                    resolution = "1",
                    rangeFrom = today,
                    rangeTo = today,
                    showError = false
                )
                if (res1min != null && res1min.has("candles")) {
                    val candles = res1min.getJSONArray("candles")
                    if (candles.length() > 0) {
                        fetchedPrice = candles.getJSONArray(0).getDouble(1) // Open of first 1-min candle
                        println("📊 [Live] Fetched Spot from Today's 1-min candles: $fetchedPrice")
                    }
                }

                // Try 2: If 1-min not available, try Daily candle for today
                if (fetchedPrice == null) {
                    val resDaily = viewModel.fetchHistoricalData(
                        appId = appId,
                        accessToken = accessToken,
                        symbol = indexSymbol,
                        resolution = "D",
                        rangeFrom = today,
                        rangeTo = today,
                        showError = false
                    )
                    if (resDaily != null && resDaily.has("candles")) {
                        val candles = resDaily.getJSONArray("candles")
                        if (candles.length() > 0) {
                            fetchedPrice = candles.getJSONArray(0).getDouble(4) // Close of daily candle
                            println("📊 [Live] Fetched Spot from Today's Daily candle: $fetchedPrice")
                        }
                    }
                }

                // Try 3: Walk backwards up to 5 days to find last trading day's Daily candle (handles weekends, holidays, pre-market start)
                if (fetchedPrice == null) {
                    var checkDate = LocalDate.parse(today).minusDays(1)
                    repeat(5) {
                        val dateStr = checkDate.toString()
                        val resPast = viewModel.fetchHistoricalData(
                            appId = appId,
                            accessToken = accessToken,
                            symbol = indexSymbol,
                            resolution = "D",
                            rangeFrom = dateStr,
                            rangeTo = dateStr,
                            showError = false
                        )
                        if (resPast != null && resPast.has("candles")) {
                            val candles = resPast.getJSONArray("candles")
                            if (candles.length() > 0) {
                                fetchedPrice = candles.getJSONArray(0).getDouble(4) // Previous close
                                println("📊 [Live] Fetched Spot from Previous Trading Day ($dateStr) Daily candle: $fetchedPrice")
                                return@repeat
                            }
                        }
                        checkDate = checkDate.minusDays(1)
                    }
                }

                if (fetchedPrice == null || fetchedPrice <= 0.0) {
                    throw IllegalStateException("Received empty or null spot history for Nifty 50 across all fallbacks for $today")
                }
                fetchedPrice
            }
        } catch (e: Exception) {
            val errMessage = "🚨 [Fyers Live Critical Error] Failed to fetch Nifty spot price after retries: ${e.message}"
            println("❌ $errMessage")
            TelegramNotifier.sendAlert(errMessage)
            throw e
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

        if (allCandidateSymbols.isEmpty()) {
            val errMessage = "🚨 [Fyers Live Critical Error] Failed to resolve any candidate option symbols for ATM strike $atmStrike."
            println("❌ $errMessage")
            TelegramNotifier.sendAlert(errMessage)
            throw IllegalStateException(errMessage)
        }

        println("🔌 [FyersSocket] Connecting via ConnectHSM(ChannelModes.LITE)...")
        scope.launch {
            TelegramNotifier.sendAlert("🟢 [Fyers Live] Connected via official FyersSocket HSM LITE mode.")
        }

        val entryResultDeferred = CompletableDeferred<LiveTradeSignal?>()
        val symbolPricesAt925 = mutableMapOf<String, Double>()
        val lastPrices = mutableMapOf<String, Double>()
        var isSnapshotCaptured = false
        var selectedCeSymbol: String? = null
        var selectedPeSymbol: String? = null

        var sessionActive = true

        val fyersSocket = FyersSocket(3)
        setSocketCredentials(fyersSocket, appId, accessToken)

        fyersSocket.webSocketDelegate = object : FyersSocketDelegate {
            override fun OnOpen(msg: String?) {
                println("🟢 [FyersSocket] Opened successfully. Subscribing to ${allCandidateSymbols.size} symbols via SubscribeData.")
                try {
                    fyersSocket.SubscribeData(allCandidateSymbols)
                } catch (e: Exception) {
                    println("⚠️ [Subscribe Error]: ${e.message}")
                }
            }

            override fun OnMessage(json: JSONObject?) {}

            override fun OnScrips(json: JSONObject?) {
                try {
                    if (json == null) return
                    val symbol = json.optString("symbol", json.optString("s", ""))
                    val ltp = json.optDouble("ltp", json.optDouble("lp", 0.0))

                    if (symbol.isNotEmpty() && ltp > 0.0) {
                        // Phase 1: Capture 9:25 AM Snapshot using the first received tick for each symbol (clock-drift immune)
                        if (!isSnapshotCaptured) {
                            if (!symbolPricesAt925.containsKey(symbol)) {
                                symbolPricesAt925[symbol] = ltp
                                lastPrices[symbol] = ltp
                            }
                            // Once we have collected quotes for a substantial number of symbols (or after 5 seconds), trigger selection
                            if (symbolPricesAt925.size >= (allCandidateSymbols.size * 0.7).toInt() || symbolPricesAt925.size >= 25) {
                                isSnapshotCaptured = true
                                var bestCeScore = Double.MAX_VALUE
                                var bestPeScore = Double.MAX_VALUE
                                var ce925Price = 0.0
                                var pe925Price = 0.0

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

                                println("📌 [FyersSocket Snapshot] Selected Live CE: $selectedCeSymbol @ ₹$ce925Price | Selected Live PE: $selectedPeSymbol @ ₹$pe925Price")

                                val ceSym = selectedCeSymbol
                                val peSym = selectedPeSymbol
                                if (ceSym != null && peSym != null) {
                                    scope.launch {
                                        TelegramNotifier.sendAlert("🎯 [9:25 AM Trade Started - SDK WebSocket] Watchlisted Strikes:\n🟢 CE: $ceSym @ ₹%.2f\n🔴 PE: $peSym @ ₹%.2f".format(ce925Price, pe925Price))
                                    }
                                    // Focus subscription strictly on selected CE and PE
                                    try { fyersSocket.UnSubscribeData(allCandidateSymbols) } catch (_: Exception) {}
                                    try { fyersSocket.SubscribeData(listOf(ceSym, peSym)) } catch (_: Exception) {}
                                }
                            }
                        }

                        // Phase 2: Observation & Breakout Window (9:25 to 9:30 AM)
                        val ceSym = selectedCeSymbol
                        val peSym = selectedPeSymbol
                        val nowTime = LocalTime.now()
                        if (isSnapshotCaptured && ceSym != null && peSym != null) {
                            val observationEnd = LocalTime.of(9, 30, 0)
                            if (nowTime.isBefore(observationEnd)) {
                                val prevPrice = lastPrices[symbol] ?: ltp
                                if (symbol == ceSym) {
                                    if (prevPrice < 180.0 && ltp >= 180.0) {
                                        println("🚀 [SDK WS BREAKOUT] CE ($symbol) crossed upward through 180 at $nowTime with LTP $ltp")
                                        sessionActive = false
                                        entryResultDeferred.complete(LiveTradeSignal(symbol, "CE", ltp, nowTime.toString()))
                                        try { fyersSocket.Close() } catch (_: Exception) {}
                                    }
                                    lastPrices[symbol] = ltp
                                } else if (symbol == peSym) {
                                    if (prevPrice < 180.0 && ltp >= 180.0) {
                                        println("🚀 [SDK WS BREAKOUT] PE ($symbol) crossed upward through 180 at $nowTime with LTP $ltp")
                                        sessionActive = false
                                        entryResultDeferred.complete(LiveTradeSignal(symbol, "PE", ltp, nowTime.toString()))
                                        try { fyersSocket.Close() } catch (_: Exception) {}
                                    }
                                    lastPrices[symbol] = ltp
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️ [FyersSocket Message Error]: ${e.message}")
                }
            }

            override fun OnClose(msg: String?) {
                println("🔒 [FyersSocket] Closed: $msg.")
                if (sessionActive && LocalTime.now().isBefore(LocalTime.of(15, 25, 0))) {
                    println("Attempting auto-reconnect...")
                    try {
                        fyersSocket.ConnectHSM(ChannelModes.LITE)
                    } catch (_: Exception) {}
                } else {
                    println("Session ended or market closed. Skipping auto-reconnect.")
                }
            }

            override fun OnError(json: JSONObject?) {
                println("❌ [FyersSocket Error]: $json.")
                scope.launch {
                    TelegramNotifier.sendAlert("🚨 [Fyers Live Error] WebSocket encountered an error: $json")
                }
            }

            override fun OnDepth(json: JSONObject?) {}
            override fun OnIndex(json: JSONObject?) {}
            override fun OnOrder(json: JSONObject?) {}
            override fun OnPosition(json: JSONObject?) {}
            override fun OnTrade(json: JSONObject?) {}
        }

        try {
            fyersSocket.ConnectHSM(ChannelModes.LITE)
        } catch (e: Exception) {
            val errMessage = "🚨 [Fyers Live Critical Error] Failed to connect WebSocket: ${e.message}"
            println("❌ $errMessage")
            TelegramNotifier.sendAlert(errMessage)
            throw e
        }

        // Wait until 9:30 AM or breakout signal
        val signal = try {
            kotlinx.coroutines.withTimeout(6 * 60 * 1000L) {
                while (LocalTime.now().isBefore(LocalTime.of(9, 30, 0))) {
                    if (entryResultDeferred.isCompleted) {
                        return@withTimeout entryResultDeferred.await()
                    }
                    delay(500L)
                }
                sessionActive = false
                entryResultDeferred.complete(null)
                null
            }
        } catch (_: Exception) {
            sessionActive = false
            null
        }

        try {
            fyersSocket.Close()
        } catch (_: Exception) {}

        if (signal == null) {
            println("⏸️ [No Trade] Neither CE nor PE crossed upward through ₹180 between 9:25 and 9:30 AM.")
            scope.launch {
                TelegramNotifier.sendAlert("⏸️ [9:30 AM No Entry] Neither CE nor PE crossed upward through ₹180 between 9:25 and 9:30 AM. No trade today.")
            }
            return
        }

        // 4. Live Position Management via Official SDK WebSocket
        executeLivePositionManagement(appId, accessToken, signal, quantity)
    }

    private suspend fun executeLivePositionManagement(appId: String, accessToken: String, signal: LiveTradeSignal, quantity: Int) {
        println("🎯 [SDK Position Active] ${signal.optionType} (${signal.symbol}) | Entry: ${signal.entryPrice} at ${signal.entryTime}")
        
        scope.launch {
            TelegramNotifier.sendAlert("🚀 [Entry Taken - SDK WS] Entered ${signal.optionType} (${signal.symbol}) at ₹%.2f | Initial SL: ₹160 | Target: ₹220".format(signal.entryPrice))
        }

        var stopLoss = 160.0 // Initial SL
        var highestPrice = signal.entryPrice
        var isPositionOpen = true
        var isTrailingActive = false

        val exitSignalDeferred = CompletableDeferred<Boolean>()

        val fyersSocket = FyersSocket(3)
        setSocketCredentials(fyersSocket, appId, accessToken)

        fyersSocket.webSocketDelegate = object : FyersSocketDelegate {
            override fun OnOpen(msg: String?) {
                println("🟢 [Management FyersSocket] Opened. Subscribing to active trade: ${signal.symbol}")
                try {
                    fyersSocket.SubscribeData(listOf(signal.symbol))
                } catch (_: Exception) {}
            }

            override fun OnMessage(json: JSONObject?) {}

            override fun OnScrips(json: JSONObject?) {
                if (!isPositionOpen || json == null) return
                try {
                    val symbol = json.optString("symbol", json.optString("s", ""))
                    val ltp = json.optDouble("ltp", json.optDouble("lp", 0.0))

                    if (symbol == signal.symbol && ltp > 0.0) {
                        val nowTime = LocalTime.now()

                        if (ltp > highestPrice) {
                            highestPrice = ltp
                        }

                        // Post 1:2 Target Trailing Mechanism
                        if (!isTrailingActive && highestPrice >= 220.0) {
                            isTrailingActive = true
                            stopLoss = 220.0
                            println("🚀 [Live 1:2 Target Reached (₹220)] Trailing activated! SL moved to ₹220.00")
                            scope.launch {
                                TelegramNotifier.sendAlert("🚀 [Fyers Live Trade] 1:2 Target Reached! Trailing active, SL locked at ₹220")
                            }
                        } else if (isTrailingActive) {
                            val newSl = max(220.0, highestPrice - 15.0)
                            if (newSl > stopLoss) {
                                stopLoss = newSl
                                println("📈 [Live Trailing SL Updated] Peak High: ₹$highestPrice -> Trailing SL: ₹$stopLoss")
                            }
                        }

                        println("📊 [SDK WS TICK] Time: $nowTime | LTP: ₹$ltp | Active SL: ₹$stopLoss | Trailing Active: $isTrailingActive")

                        // Stop Loss Hit Check
                        if (ltp <= stopLoss) {
                            isPositionOpen = false
                            val exitReason = if (isTrailingActive) "Trailing SL Hit" else "Initial Stop Loss Hit"
                            val pnl = (stopLoss - signal.entryPrice) * quantity
                            println("🛑 [SDK WS SL HIT] Exit at $stopLoss | P&L: ₹%.2f".format(pnl))
                            scope.launch {
                                TelegramNotifier.sendAlert("🏁 [Trade Exit - $exitReason] Exited ${signal.optionType} (${signal.symbol}) at ₹$stopLoss | P&L: ₹%.2f".format(pnl))
                            }
                            try { fyersSocket.Close() } catch (_: Exception) {}
                            exitSignalDeferred.complete(true)
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
                                try { fyersSocket.Close() } catch (_: Exception) {}
                                exitSignalDeferred.complete(true)
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("⚠️ [Management FyersSocket Error]: ${e.message}")
                }
            }

            override fun OnClose(msg: String?) {
                println("🔒 [Management FyersSocket] Closed.")
                if (isPositionOpen && LocalTime.now().isBefore(LocalTime.of(15, 25, 0))) {
                    println("Attempting reconnect...")
                    try {
                        fyersSocket.ConnectHSM(ChannelModes.LITE)
                    } catch (_: Exception) {}
                } else {
                    println("Position closed or market closed. Skipping reconnect.")
                }
            }

            override fun OnError(json: JSONObject?) {
                println("❌ [Management FyersSocket Error]: $json")
                scope.launch {
                    TelegramNotifier.sendAlert("🚨 [Fyers Live Error] Management WebSocket error: $json")
                }
            }

            override fun OnDepth(json: JSONObject?) {}
            override fun OnIndex(json: JSONObject?) {}
            override fun OnOrder(json: JSONObject?) {}
            override fun OnPosition(json: JSONObject?) {}
            override fun OnTrade(json: JSONObject?) {}
        }

        // Wait 3 seconds for previous socket connection slot to be released by Fyers server
        delay(3000L)

        try {
            retryWithBackoff(maxRetries = 5) {
                fyersSocket.ConnectHSM(ChannelModes.LITE)
            }
        } catch (e: Exception) {
            val errMessage = "🚨 [Fyers Live Critical Error] Failed to connect management WebSocket after retries: ${e.message}"
            println("❌ $errMessage")
            TelegramNotifier.sendAlert(errMessage)
        }

        // Keep active until position closes or 3:25 PM market close
        while (isPositionOpen) {
            if (LocalTime.now().isAfter(LocalTime.of(15, 25, 0))) {
                isPositionOpen = false
                val pnl = (highestPrice - signal.entryPrice) * quantity
                println("⏰ [Market Close] Position closed at session end.")
                scope.launch {
                    TelegramNotifier.sendAlert("🏁 [Trade Exit - Session End] Position closed at session end | P&L: ₹%.2f".format(pnl))
                }
                try {
                    fyersSocket.Close()
                } catch (_: Exception) {}
                break
            }
            if (exitSignalDeferred.isCompleted) {
                break
            }
            delay(1000L)
        }
    }

    private suspend fun <T> retryWithBackoff(maxRetries: Int = 3, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                attempt++
                if (attempt > maxRetries) throw e
                val backoffMillis = 1000L * (1 shl attempt) // 2s, 4s, 8s
                println("⚠️ [Live API Error] Retrying in ${backoffMillis}ms (Attempt $attempt/$maxRetries)...: ${e.message}")
                delay(backoffMillis)
            }
        }
    }

    private fun setSocketCredentials(socket: FyersSocket, clientId: String, token: String) {
        try {
            val idField = FyersSocket::class.java.getDeclaredField("ClientId")
            idField.isAccessible = true
            idField.set(socket, clientId)

            val tokenField = FyersSocket::class.java.getDeclaredField("AccessToken")
            tokenField.isAccessible = true
            tokenField.set(socket, token)
        } catch (_: Exception) {}
    }

    private data class LiveTradeSignal(
        val symbol: String,
        val optionType: String,
        val entryPrice: Double,
        val entryTime: String
    )
}
