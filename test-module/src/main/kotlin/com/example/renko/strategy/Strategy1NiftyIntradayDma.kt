package com.example.renko.strategy

import com.example.renko.engine.RenkoChartEngine
import com.example.renko.indicator.RenkoIndicators
import com.example.renko.model.*
import com.example.renko.pattern.RenkoPatternDetector
import com.example.renko.target.RenkoTargetCalculator

/**
 * Strategy 1 from Renko.pdf (Pages 144-146):
 * Trading Nifty Futures (NF) with Dual Moving Average (DMA) Intraday Strategy
 *
 * Parameters from PDF:
 * - Instrument: Nifty 50 Index / Futures
 * - Box Size: 0.05% (1-Min Chart)
 * - Indicators: 20 Brick EMA & 40 Brick EMA
 * - Bullish Rule: 20 EMA > 40 EMA -> Trade Bullish Patterns (Swing Breakout, One-Back, Two-Back)
 * - Bearish Rule: 20 EMA < 40 EMA -> Trade Bearish Patterns (Swing Breakdown, One-Back, Two-Back)
 * - Stop Loss: Exactly 1 Brick below Swing Low (Long) / 1 Brick above Swing High (Short)
 */
object Strategy1NiftyIntradayDma {

    val boxSizePercent: Double = 0.05 // Exactly 0.05% as per PDF Page 13

    fun evaluate(symbol: String, bricks: List<RenkoBrick>): List<RenkoTradeSignal> {
        val signals = mutableListOf<RenkoTradeSignal>()
        if (bricks.size < 40) return signals

        val ema20 = RenkoIndicators.calculateEMA(bricks, 20)
        val ema40 = RenkoIndicators.calculateEMA(bricks, 40)
        val patterns = RenkoPatternDetector.detectPatterns(bricks)

        for (pattern in patterns) {
            val idx = pattern.brickIndex
            val e20 = ema20[idx] ?: continue
            val e40 = ema40[idx] ?: continue
            val brick = bricks[idx]

            // Bullish Entry Condition: 20 EMA > 40 EMA
            if (e20 > e40 && (
                        pattern.patternType == RenkoPatternType.BULLISH_SWING_BREAKOUT ||
                                pattern.patternType == RenkoPatternType.ONE_BACK_BULLISH ||
                                pattern.patternType == RenkoPatternType.TWO_BACK_BULLISH
                        )) {
                val pullbackBricks = if (pattern.patternType == RenkoPatternType.TWO_BACK_BULLISH) 2 else 1
                val target = RenkoTargetCalculator.calculateOneTwoBackTarget(
                    breakoutPrice = pattern.price,
                    pullbackBrickCount = pullbackBricks,
                    brickValue = brick.brickSize,
                    isBullish = true
                )
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.BUY,
                        strategyName = "Strategy 1: NF Intraday DMA Bullish",
                        symbol = symbol,
                        entryPrice = pattern.price,
                        stopLoss = pattern.stopLoss,
                        target = target,
                        timestamp = pattern.timestamp,
                        reason = "20 EMA ($e20) > 40 EMA ($e40) + ${pattern.patternType}"
                    )
                )
            }
            // Bearish Entry Condition: 20 EMA < 40 EMA
            else if (e20 < e40 && (
                        pattern.patternType == RenkoPatternType.BEARISH_SWING_BREAKDOWN ||
                                pattern.patternType == RenkoPatternType.ONE_BACK_BEARISH ||
                                pattern.patternType == RenkoPatternType.TWO_BACK_BEARISH
                        )) {
                val pullbackBricks = if (pattern.patternType == RenkoPatternType.TWO_BACK_BEARISH) 2 else 1
                val target = RenkoTargetCalculator.calculateOneTwoBackTarget(
                    breakoutPrice = pattern.price,
                    pullbackBrickCount = pullbackBricks,
                    brickValue = brick.brickSize,
                    isBullish = false
                )
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.SELL,
                        strategyName = "Strategy 1: NF Intraday DMA Bearish",
                        symbol = symbol,
                        entryPrice = pattern.price,
                        stopLoss = pattern.stopLoss,
                        target = target,
                        timestamp = pattern.timestamp,
                        reason = "20 EMA ($e20) < 40 EMA ($e40) + ${pattern.patternType}"
                    )
                )
            }
        }

        return signals
    }
}
