package com.example.renko.strategy

import com.example.renko.indicator.RenkoIndicators
import com.example.renko.model.*
import com.example.renko.pattern.RenkoPatternDetector
import com.example.renko.target.RenkoTargetCalculator

/**
 * Strategy 12 from Renko.pdf (Pages 195-197):
 * Option Selling Strategy (Adaptive RSI)
 *
 * Parameters from PDF:
 * - Instrument: Options (Index & Stock Options)
 * - Box Size: 3.0% (1-Min Chart)
 * - Indicator: Adaptive RSI ARSI (14, 9)
 * - Rule: ARSI (14, 9) < 50
 * - Short Entry: Swing Breakdown Pattern
 * - Stop Loss: 1 Brick above Swing High
 */
object Strategy12OptionSellingArsiIntraday {

    val optionBoxSizePercent: Double = 3.0 // PDF Page 196 & 197

    fun evaluate(optionSymbol: String, optionBricks: List<RenkoBrick>): List<RenkoTradeSignal> {
        val signals = mutableListOf<RenkoTradeSignal>()
        if (optionBricks.size < 20) return signals

        val arsi = RenkoIndicators.calculateAdaptiveRSI(optionBricks, 14, 9)
        val patterns = RenkoPatternDetector.detectPatterns(optionBricks)

        for (pattern in patterns) {
            val idx = pattern.brickIndex
            val arsiVal = arsi[idx] ?: continue
            val brick = optionBricks[idx]

            // Short Entry Condition: ARSI < 50 + Bearish Swing Breakdown Pattern
            if (arsiVal < 50.0 && pattern.patternType == RenkoPatternType.BEARISH_SWING_BREAKDOWN) {
                val target = RenkoTargetCalculator.calculateSwingExtensionTarget(
                    swingHighOrLowPrice = pattern.price,
                    brickCount = 3,
                    brickValue = brick.brickSize,
                    isBullish = false
                )
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.SELL,
                        strategyName = "Strategy 12: Option Selling ARSI + Swing Breakdown",
                        symbol = optionSymbol,
                        entryPrice = pattern.price,
                        stopLoss = pattern.stopLoss,
                        target = target,
                        timestamp = pattern.timestamp,
                        reason = "ARSI ($arsiVal) < 50 + Bearish Swing Breakdown in 3% Option Chart"
                    )
                )
            }
        }

        return signals
    }
}
