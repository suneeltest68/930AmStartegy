package com.example.renko.strategy

import com.example.renko.indicator.RenkoIndicators
import com.example.renko.model.*
import com.example.renko.pattern.RenkoPatternDetector
import com.example.renko.target.RenkoTargetCalculator

object RenkoStrategies {

    /**
     * Strategy 1: Trading NF with DMA (Intraday Strategy)
     * PDF Pages 144-146 | Box Size: 0.05% (1-Min)
     */
    fun evaluateStrategy1DmaIntraday(
        symbol: String,
        bricks: List<RenkoBrick>
    ): List<RenkoTradeSignal> {
        val signals = mutableListOf<RenkoTradeSignal>()
        if (bricks.size < 40) return signals

        val ema20 = RenkoIndicators.calculateEMA(bricks, 20)
        val ema40 = RenkoIndicators.calculateEMA(bricks, 40)
        val patterns = RenkoPatternDetector.detectPatterns(bricks)

        for (pattern in patterns) {
            val idx = pattern.brickIndex
            val e20 = ema20[idx] ?: continue
            val e40 = ema40[idx] ?: continue

            val isBullishDma = e20 > e40
            val isBearishDma = e20 < e40

            if (isBullishDma && (
                        pattern.patternType == RenkoPatternType.BULLISH_SWING_BREAKOUT ||
                                pattern.patternType == RenkoPatternType.ONE_BACK_BULLISH ||
                                pattern.patternType == RenkoPatternType.TWO_BACK_BULLISH
                        )) {
                val target = RenkoTargetCalculator.calculateOneTwoBackTarget(
                    breakoutPrice = pattern.price,
                    pullbackBrickCount = if (pattern.patternType == RenkoPatternType.TWO_BACK_BULLISH) 2 else 1,
                    brickValue = bricks[idx].brickSize,
                    isBullish = true
                )
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.BUY,
                        strategyName = "Strategy 1: Intraday DMA Bullish",
                        symbol = symbol,
                        entryPrice = pattern.price,
                        stopLoss = pattern.stopLoss,
                        target = target,
                        timestamp = pattern.timestamp,
                        reason = "20 EMA > 40 EMA + ${pattern.patternType} pattern"
                    )
                )
            } else if (isBearishDma && (
                        pattern.patternType == RenkoPatternType.BEARISH_SWING_BREAKDOWN ||
                                pattern.patternType == RenkoPatternType.ONE_BACK_BEARISH ||
                                pattern.patternType == RenkoPatternType.TWO_BACK_BEARISH
                        )) {
                val target = RenkoTargetCalculator.calculateOneTwoBackTarget(
                    breakoutPrice = pattern.price,
                    pullbackBrickCount = if (pattern.patternType == RenkoPatternType.TWO_BACK_BEARISH) 2 else 1,
                    brickValue = bricks[idx].brickSize,
                    isBullish = false
                )
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.SELL,
                        strategyName = "Strategy 1: Intraday DMA Bearish",
                        symbol = symbol,
                        entryPrice = pattern.price,
                        stopLoss = pattern.stopLoss,
                        target = target,
                        timestamp = pattern.timestamp,
                        reason = "20 EMA < 40 EMA + ${pattern.patternType} pattern"
                    )
                )
            }
        }

        return signals
    }

    /**
     * Strategy 2: Trading NF with DMA (Positional Strategy)
     * PDF Pages 147-149 | Box Size: 0.10%
     */
    fun evaluateStrategy2DmaPositional(
        symbol: String,
        bricks: List<RenkoBrick>
    ): List<RenkoTradeSignal> {
        val signals = mutableListOf<RenkoTradeSignal>()
        if (bricks.size < 40) return signals

        val ema20 = RenkoIndicators.calculateEMA(bricks, 20)
        val ema40 = RenkoIndicators.calculateEMA(bricks, 40)

        for (i in 1 until bricks.size) {
            val currE20 = ema20[i] ?: continue
            val currE40 = ema40[i] ?: continue
            val prevE20 = ema20[i - 1] ?: continue
            val prevE40 = ema40[i - 1] ?: continue

            // Bullish Crossover: 20 EMA crosses above 40 EMA
            if (prevE20 <= prevE40 && currE20 > currE40) {
                val brick = bricks[i]
                val sl = brick.close - (3 * brick.brickSize)
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.BUY,
                        strategyName = "Strategy 2: NF Positional DMA Bullish Crossover",
                        symbol = symbol,
                        entryPrice = brick.close,
                        stopLoss = sl,
                        target = brick.close + (6 * brick.brickSize),
                        timestamp = brick.timestamp,
                        reason = "20 EMA ($currE20) crossed above 40 EMA ($currE40)"
                    )
                )
            }
            // Bearish Crossover: 20 EMA crosses below 40 EMA
            else if (prevE20 >= prevE40 && currE20 < currE40) {
                val brick = bricks[i]
                val sl = brick.close + (3 * brick.brickSize)
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.EXIT_BUY,
                        strategyName = "Strategy 2: NF Positional DMA Bearish Crossover Exit",
                        symbol = symbol,
                        entryPrice = brick.close,
                        stopLoss = sl,
                        target = brick.close - (6 * brick.brickSize),
                        timestamp = brick.timestamp,
                        reason = "20 EMA ($currE20) crossed below 40 EMA ($currE40)"
                    )
                )
            }
        }

        return signals
    }

    /**
     * Strategy 3: Trading NF with SuperTrend (Positional Strategy)
     * PDF Pages 150-155 | Box Size: 0.10% | SuperTrend (40, 12)
     */
    fun evaluateStrategy3SuperTrend(
        symbol: String,
        bricks: List<RenkoBrick>,
        period: Int = 40,
        multiplier: Double = 12.0
    ): List<RenkoTradeSignal> {
        val signals = mutableListOf<RenkoTradeSignal>()
        val stValues = RenkoIndicators.calculateSuperTrend(bricks, period, multiplier)

        for (i in 2 until bricks.size) {
            val currSt = stValues[i] ?: continue
            val prevSt = stValues[i - 1] ?: continue
            val prevPrevSt = stValues[i - 2]

            // Long entry at 2nd brick after SuperTrend (40, 12) bullish cross
            if (currSt.isBullish && prevSt.isBullish && (prevPrevSt == null || !prevPrevSt.isBullish)) {
                val brick = bricks[i]
                val sl = brick.open - brick.brickSize
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.BUY,
                        strategyName = "Strategy 3: SuperTrend Bullish Entry",
                        symbol = symbol,
                        entryPrice = brick.close,
                        stopLoss = sl,
                        target = brick.close + (3 * brick.brickSize),
                        timestamp = brick.timestamp,
                        reason = "2nd brick after SuperTrend ($period, $multiplier) turned Bullish"
                    )
                )
            } else if (!currSt.isBullish && !prevSt.isBullish && (prevPrevSt == null || prevPrevSt.isBullish)) {
                val brick = bricks[i]
                val sl = brick.open + brick.brickSize
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.SELL,
                        strategyName = "Strategy 3: SuperTrend Bearish Entry",
                        symbol = symbol,
                        entryPrice = brick.close,
                        stopLoss = sl,
                        target = brick.close - (3 * brick.brickSize),
                        timestamp = brick.timestamp,
                        reason = "2nd brick after SuperTrend ($period, $multiplier) turned Bearish"
                    )
                )
            }
        }

        return signals
    }

    /**
     * Strategy 4: Trading BNF with DMA (Intraday Strategy)
     * PDF Pages 156-158 | Box Size: 0.08% (1-Min)
     */
    fun evaluateStrategy4BankNiftyDmaIntraday(
        symbol: String,
        bricks: List<RenkoBrick>
    ): List<RenkoTradeSignal> {
        return evaluateStrategy1DmaIntraday(symbol, bricks)
    }

    /**
     * Strategy 5: Trading BNF with DMA (Positional Strategy)
     * PDF Pages 159-161 | Box Size: 0.15%
     */
    fun evaluateStrategy5BankNiftyDmaPositional(
        symbol: String,
        bricks: List<RenkoBrick>
    ): List<RenkoTradeSignal> {
        return evaluateStrategy2DmaPositional(symbol, bricks)
    }

    /**
     * Strategy 6: Trading BNF with SuperTrend (Positional Strategy)
     * PDF Pages 162-166 | Box Size: 0.15% | SuperTrend (40, 12)
     */
    fun evaluateStrategy6BankNiftySuperTrendPositional(
        symbol: String,
        bricks: List<RenkoBrick>
    ): List<RenkoTradeSignal> {
        return evaluateStrategy3SuperTrend(symbol, bricks, 40, 12.0)
    }

    /**
     * Strategy 7: Multi-Swing Breakout / Breakdown Strategy
     * PDF Pages 167-171 | Box Size: 0.05% / 0.08%
     */
    fun evaluateStrategy7MultiSwing(
        symbol: String,
        bricks: List<RenkoBrick>
    ): List<RenkoTradeSignal> {
        val signals = mutableListOf<RenkoTradeSignal>()
        if (bricks.size < 40) return signals

        val ema20 = RenkoIndicators.calculateEMA(bricks, 20)
        val ema40 = RenkoIndicators.calculateEMA(bricks, 40)
        val patterns = RenkoPatternDetector.detectPatterns(bricks)

        for (pattern in patterns) {
            val idx = pattern.brickIndex
            val e20 = ema20[idx] ?: continue
            val e40 = ema40[idx] ?: continue

            if (pattern.patternType == RenkoPatternType.MULTI_SWING_BREAKOUT && e20 > e40) {
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.BUY,
                        strategyName = "Strategy 7: Multi-Swing Bullish Breakout",
                        symbol = symbol,
                        entryPrice = pattern.price,
                        stopLoss = pattern.stopLoss,
                        target = pattern.price + (4 * bricks[idx].brickSize),
                        timestamp = pattern.timestamp,
                        reason = "20 EMA > 40 EMA + Multi-Swing Bullish Breakout"
                    )
                )
            } else if (pattern.patternType == RenkoPatternType.MULTI_SWING_BREAKDOWN && e20 < e40) {
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.SELL,
                        strategyName = "Strategy 7: Multi-Swing Bearish Breakdown",
                        symbol = symbol,
                        entryPrice = pattern.price,
                        stopLoss = pattern.stopLoss,
                        target = pattern.price - (4 * bricks[idx].brickSize),
                        timestamp = pattern.timestamp,
                        reason = "20 EMA < 40 EMA + Multi-Swing Bearish Breakdown"
                    )
                )
            }
        }

        return signals
    }

    /**
     * Strategy 8: Top Down Trading Strategy (Multi-Timeframe Alignment)
     * PDF Pages 172-182 | Higher Timeframe (Daily 0.25%) + Lower Timeframe (1-Min 0.05%)
     */
    fun evaluateStrategy8TopDown(
        symbol: String,
        lowerTfBricks: List<RenkoBrick>,
        isHigherTfBullish: Boolean
    ): List<RenkoTradeSignal> {
        val signals = mutableListOf<RenkoTradeSignal>()
        if (lowerTfBricks.size < 40) return signals

        val ema20 = RenkoIndicators.calculateEMA(lowerTfBricks, 20)
        val ema40 = RenkoIndicators.calculateEMA(lowerTfBricks, 40)
        val patterns = RenkoPatternDetector.detectPatterns(lowerTfBricks)

        for (pattern in patterns) {
            val idx = pattern.brickIndex
            val e20 = ema20[idx] ?: continue
            val e40 = ema40[idx] ?: continue

            // Higher Timeframe Bullish -> Align with Lower Timeframe Bullish Signals
            if (isHigherTfBullish && e20 > e40 && (
                        pattern.patternType == RenkoPatternType.BULLISH_SWING_BREAKOUT ||
                                pattern.patternType == RenkoPatternType.TWO_BACK_BULLISH
                        )) {
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.BUY,
                        strategyName = "Strategy 8: Top-Down Bullish Alignment",
                        symbol = symbol,
                        entryPrice = pattern.price,
                        stopLoss = pattern.stopLoss,
                        target = pattern.price + (3 * lowerTfBricks[idx].brickSize),
                        timestamp = pattern.timestamp,
                        reason = "Higher TF Bullish + 1-Min 20 EMA > 40 EMA + ${pattern.patternType}"
                    )
                )
            }
            // Higher Timeframe Bearish -> Align with Lower Timeframe Bearish Signals
            else if (!isHigherTfBullish && e20 < e40 && (
                        pattern.patternType == RenkoPatternType.BEARISH_SWING_BREAKDOWN ||
                                pattern.patternType == RenkoPatternType.ONE_BACK_BEARISH
                        )) {
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.SELL,
                        strategyName = "Strategy 8: Top-Down Bearish Alignment",
                        symbol = symbol,
                        entryPrice = pattern.price,
                        stopLoss = pattern.stopLoss,
                        target = pattern.price - (3 * lowerTfBricks[idx].brickSize),
                        timestamp = pattern.timestamp,
                        reason = "Higher TF Bearish + 1-Min 20 EMA < 40 EMA + ${pattern.patternType}"
                    )
                )
            }
        }

        return signals
    }

    /**
     * Strategy 9: TMA + RSI Positional Strategy
     * PDF Pages 183-186 | Box Size: 1.0%
     */
    fun evaluateStrategy9TmaRsi(
        symbol: String,
        bricks: List<RenkoBrick>
    ): List<RenkoTradeSignal> {
        val signals = mutableListOf<RenkoTradeSignal>()
        if (bricks.size < 40) return signals

        val tema20 = RenkoIndicators.calculateTEMA(bricks, 20)
        val tema30 = RenkoIndicators.calculateTEMA(bricks, 30)
        val tema40 = RenkoIndicators.calculateTEMA(bricks, 40)
        val rsi = RenkoIndicators.calculateRSI(bricks, 14)

        for (i in 2 until bricks.size) {
            val t20 = tema20[i] ?: continue
            val t30 = tema30[i] ?: continue
            val t40 = tema40[i] ?: continue
            val rsiVal = rsi[i] ?: continue

            val isTmaBullish = t20 > t30 && t30 > t40
            val isRsiBullish = rsiVal > 50.0

            val currBrick = bricks[i]
            val prevBrick = bricks[i - 1]

            // First bullish brick after a bearish brick
            if (isTmaBullish && isRsiBullish && currBrick.isBullish && prevBrick.isBearish) {
                val sl = currBrick.close * 0.98 // 2% SL as specified on page 184
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.BUY,
                        strategyName = "Strategy 9: TMA + RSI Positional",
                        symbol = symbol,
                        entryPrice = currBrick.close,
                        stopLoss = sl,
                        target = currBrick.close * 1.05, // 5% Target
                        timestamp = currBrick.timestamp,
                        reason = "TEMA(20 > 30 > 40) + RSI ($rsiVal > 50) + Bullish Brick Reversal"
                    )
                )
            }
        }

        return signals
    }

    /**
     * Strategy 10: Donchian Channel & Option Buying Nifty Intraday Strategy
     * PDF Pages 187-191 | Spot: 0.075% Box Size, DC(40) | Option: 3.0% Box Size, DEMA 20 > 40
     */
    fun evaluateStrategy10OptionBuyingDc(
        optionSymbol: String,
        optionBricks: List<RenkoBrick>,
        isSpotDcBullish: Boolean
    ): List<RenkoTradeSignal> {
        val signals = mutableListOf<RenkoTradeSignal>()
        if (!isSpotDcBullish || optionBricks.size < 40) return signals

        val dema20 = RenkoIndicators.calculateDEMA(optionBricks, 20)
        val dema40 = RenkoIndicators.calculateDEMA(optionBricks, 40)
        val patterns = RenkoPatternDetector.detectPatterns(optionBricks)

        for (pattern in patterns) {
            val idx = pattern.brickIndex
            val d20 = dema20[idx] ?: continue
            val d40 = dema40[idx] ?: continue

            if (d20 > d40 && pattern.patternType == RenkoPatternType.BULLISH_SWING_BREAKOUT) {
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.BUY,
                        strategyName = "Strategy 10: Option Buying DC + DEMA",
                        symbol = optionSymbol,
                        entryPrice = pattern.price,
                        stopLoss = pattern.stopLoss,
                        target = pattern.price + (3 * optionBricks[idx].brickSize),
                        timestamp = pattern.timestamp,
                        reason = "Spot DC(40) Bullish + Option DEMA 20 > 40 + Swing Breakout"
                    )
                )
            }
        }

        return signals
    }

    /**
     * Strategy 11: Option Buying Strategy (Adaptive RSI)
     * PDF Pages 192-194 | Option Box Size: 3.0% | Adaptive RSI (14, 9) > 50
     */
    fun evaluateStrategy11OptionBuyingArsi(
        optionSymbol: String,
        optionBricks: List<RenkoBrick>
    ): List<RenkoTradeSignal> {
        val signals = mutableListOf<RenkoTradeSignal>()
        if (optionBricks.size < 20) return signals

        val arsi = RenkoIndicators.calculateAdaptiveRSI(optionBricks, 14, 9)
        val patterns = RenkoPatternDetector.detectPatterns(optionBricks)

        for (pattern in patterns) {
            val idx = pattern.brickIndex
            val arsiVal = arsi[idx] ?: continue

            if (arsiVal > 50.0 && pattern.patternType == RenkoPatternType.TWO_BACK_BULLISH) {
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.BUY,
                        strategyName = "Strategy 11: Option Buying ARSI + Two-Back",
                        symbol = optionSymbol,
                        entryPrice = pattern.price,
                        stopLoss = pattern.stopLoss,
                        target = pattern.price + (3 * optionBricks[idx].brickSize),
                        timestamp = pattern.timestamp,
                        reason = "ARSI ($arsiVal > 50) + Two-Back Bullish Pattern in 3% Option Chart"
                    )
                )
            }
        }

        return signals
    }

    /**
     * Strategy 12: Option Selling Strategy (Adaptive RSI)
     * PDF Pages 195-197 | Option Box Size: 3.0% | Adaptive RSI (14, 9) < 50
     */
    fun evaluateStrategy12OptionSellingArsi(
        optionSymbol: String,
        optionBricks: List<RenkoBrick>
    ): List<RenkoTradeSignal> {
        val signals = mutableListOf<RenkoTradeSignal>()
        if (optionBricks.size < 20) return signals

        val arsi = RenkoIndicators.calculateAdaptiveRSI(optionBricks, 14, 9)
        val patterns = RenkoPatternDetector.detectPatterns(optionBricks)

        for (pattern in patterns) {
            val idx = pattern.brickIndex
            val arsiVal = arsi[idx] ?: continue

            if (arsiVal < 50.0 && pattern.patternType == RenkoPatternType.BEARISH_SWING_BREAKDOWN) {
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.SELL,
                        strategyName = "Strategy 12: Option Selling ARSI + Swing Breakdown",
                        symbol = optionSymbol,
                        entryPrice = pattern.price,
                        stopLoss = pattern.stopLoss,
                        target = pattern.price - (3 * optionBricks[idx].brickSize),
                        timestamp = pattern.timestamp,
                        reason = "ARSI ($arsiVal < 50) + Bearish Swing Breakdown in 3% Option Chart"
                    )
                )
            }
        }

        return signals
    }

    /**
     * Strategy 13: Buying ETFs Strategy
     * PDF Pages 198-200 | Box Size: 0.25% (Daily) | EMA 20 > 40 & ST (40, 14) Bullish
     */
    fun evaluateStrategy13EtfBuying(
        symbol: String,
        bricks: List<RenkoBrick>
    ): List<RenkoTradeSignal> {
        val signals = mutableListOf<RenkoTradeSignal>()
        if (bricks.size < 40) return signals

        val ema20 = RenkoIndicators.calculateEMA(bricks, 20)
        val ema40 = RenkoIndicators.calculateEMA(bricks, 40)
        val stValues = RenkoIndicators.calculateSuperTrend(bricks, 40, 14.0)
        val patterns = RenkoPatternDetector.detectPatterns(bricks)

        for (pattern in patterns) {
            val idx = pattern.brickIndex
            val e20 = ema20[idx] ?: continue
            val e40 = ema40[idx] ?: continue
            val st = stValues[idx] ?: continue

            if (e20 > e40 && st.isBullish && pattern.patternType == RenkoPatternType.BULLISH_SWING_BREAKOUT) {
                val sl = st.value - bricks[idx].brickSize
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.BUY,
                        strategyName = "Strategy 13: ETF Buying Strategy",
                        symbol = symbol,
                        entryPrice = pattern.price,
                        stopLoss = sl,
                        target = pattern.price * 1.05,
                        timestamp = pattern.timestamp,
                        reason = "EMA 20 > 40 + ST(40,14) Bullish + Swing Breakout in 0.25% Daily Chart"
                    )
                )
            }
        }

        return signals
    }
}
