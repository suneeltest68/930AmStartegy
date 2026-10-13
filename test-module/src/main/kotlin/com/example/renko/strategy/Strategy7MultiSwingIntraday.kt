package com.example.renko.strategy

import com.example.renko.indicator.RenkoIndicators
import com.example.renko.model.*
import com.example.renko.pattern.RenkoPatternDetector
import com.example.renko.target.RenkoTargetCalculator

/**
 * Strategy 7 from Renko.pdf (Pages 167-171):
 * Multi-Swing Breakout / Breakdown Intraday Strategy
 *
 * Parameters from PDF:
 * - Instrument: Nifty / BankNifty
 * - Box Size: 0.05% (Nifty) / 0.08% (BankNifty) on 1-Min Chart
 * - Indicators: 20 Brick EMA & 40 Brick EMA
 * - Bullish Entry: 20 EMA > 40 EMA + Multi-Swing Bullish Breakout (tested resistance breached)
 * - Bearish Entry: 20 EMA < 40 EMA + Multi-Swing Bearish Breakdown (tested support breached)
 * - Stop Loss: 1 Brick below Swing Low (Long) / 1 Brick above Swing High (Short)
 * - Target: Multi-Swing Extension = Projection Price +/- (Pattern Height in Bricks * Brick Value)
 */
object Strategy7MultiSwingIntraday {

    fun evaluate(symbol: String, bricks: List<RenkoBrick>): List<RenkoTradeSignal> {
        val signals = mutableListOf<RenkoTradeSignal>()
        if (bricks.size < 40) return signals

        val ema20 = RenkoIndicators.calculateEMA(bricks, 20)
        val ema40 = RenkoIndicators.calculateEMA(bricks, 40)
        val patterns = RenkoPatternDetector.detectPatterns(bricks)
        val swings = RenkoPatternDetector.extractSwings(bricks)

        for (pattern in patterns) {
            val idx = pattern.brickIndex
            val e20 = ema20[idx] ?: continue
            val e40 = ema40[idx] ?: continue
            val brick = bricks[idx]

            if (pattern.patternType == RenkoPatternType.MULTI_SWING_BREAKOUT && e20 > e40) {
                val priorBullishSwings = swings.filter { it.endIndex < idx && it.direction == RenkoDirection.BULLISH }
                val patternHeightBricks = if (priorBullishSwings.isNotEmpty()) priorBullishSwings.last().brickCount else 5
                val target = RenkoTargetCalculator.calculateMultiSwingTarget(
                    projectionPrice = pattern.price,
                    patternHeightBricks = patternHeightBricks,
                    brickValue = brick.brickSize,
                    isBullish = true
                )

                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.BUY,
                        strategyName = "Strategy 7: Multi-Swing Bullish Breakout",
                        symbol = symbol,
                        entryPrice = pattern.price,
                        stopLoss = pattern.stopLoss,
                        target = target,
                        timestamp = pattern.timestamp,
                        reason = "20 EMA ($e20) > 40 EMA ($e40) + Multi-Swing Bullish Breakout"
                    )
                )
            } else if (pattern.patternType == RenkoPatternType.MULTI_SWING_BREAKDOWN && e20 < e40) {
                val priorBearishSwings = swings.filter { it.endIndex < idx && it.direction == RenkoDirection.BEARISH }
                val patternHeightBricks = if (priorBearishSwings.isNotEmpty()) priorBearishSwings.last().brickCount else 5
                val target = RenkoTargetCalculator.calculateMultiSwingTarget(
                    projectionPrice = pattern.price,
                    patternHeightBricks = patternHeightBricks,
                    brickValue = brick.brickSize,
                    isBullish = false
                )

                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.SELL,
                        strategyName = "Strategy 7: Multi-Swing Bearish Breakdown",
                        symbol = symbol,
                        entryPrice = pattern.price,
                        stopLoss = pattern.stopLoss,
                        target = target,
                        timestamp = pattern.timestamp,
                        reason = "20 EMA ($e20) < 40 EMA ($e40) + Multi-Swing Bearish Breakdown"
                    )
                )
            }
        }

        return signals
    }
}
