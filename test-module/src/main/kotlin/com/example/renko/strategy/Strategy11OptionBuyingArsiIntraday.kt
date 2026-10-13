package com.example.renko.strategy

import com.example.renko.indicator.RenkoIndicators
import com.example.renko.model.*
import com.example.renko.pattern.RenkoPatternDetector
import com.example.renko.target.RenkoTargetCalculator

/**
 * Strategy 11 from Renko.pdf (Pages 192-194):
 * Option Buying Strategy (Adaptive RSI)
 *
 * Parameters from PDF:
 * - Instrument: Options (Index & Stock Options)
 * - Box Size: 3.0% (1-Min Chart)
 * - Indicator: Adaptive RSI ARSI (14, 9)
 * - Rule: ARSI (14, 9) > 50
 * - Long Entry: Two-Back Bullish Pattern
 * - Stop Loss: 1 Brick below Swing Low
 */
object Strategy11OptionBuyingArsiIntraday {

    val optionBoxSizePercent: Double = 3.0 // PDF Page 193 & 194

    fun evaluate(optionSymbol: String, optionBricks: List<RenkoBrick>): List<RenkoTradeSignal> {
        val signals = mutableListOf<RenkoTradeSignal>()
        if (optionBricks.size < 20) return signals

        val arsi = RenkoIndicators.calculateAdaptiveRSI(optionBricks, 14, 9)
        val patterns = RenkoPatternDetector.detectPatterns(optionBricks)

        for (pattern in patterns) {
            val idx = pattern.brickIndex
            val arsiVal = arsi[idx] ?: continue
            val brick = optionBricks[idx]

            // Entry Condition: ARSI > 50 + Two-Back Bullish Pattern
            if (arsiVal > 50.0 && pattern.patternType == RenkoPatternType.TWO_BACK_BULLISH) {
                val target = RenkoTargetCalculator.calculateOneTwoBackTarget(
                    breakoutPrice = pattern.price,
                    pullbackBrickCount = 2,
                    brickValue = brick.brickSize,
                    isBullish = true
                )
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.BUY,
                        strategyName = "Strategy 11: Option Buying ARSI + Two-Back",
                        symbol = optionSymbol,
                        entryPrice = pattern.price,
                        stopLoss = pattern.stopLoss,
                        target = target,
                        timestamp = pattern.timestamp,
                        reason = "ARSI ($arsiVal) > 50 + Two-Back Bullish Pattern in 3% Option Chart"
                    )
                )
            }
        }

        return signals
    }
}
