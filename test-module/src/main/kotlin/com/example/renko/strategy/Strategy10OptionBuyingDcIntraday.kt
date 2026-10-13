package com.example.renko.strategy

import com.example.renko.indicator.RenkoIndicators
import com.example.renko.model.*
import com.example.renko.pattern.RenkoPatternDetector
import com.example.renko.target.RenkoTargetCalculator

/**
 * Strategy 10 from Renko.pdf (Pages 187-191):
 * Donchian Channel & Option Buying Nifty Intraday Strategy
 *
 * Parameters from PDF:
 * - Spot Chart: 0.075% Box Size, 1-Min Chart, Donchian Channel (40)
 * - Option Chart: 3.0% Box Size, 1-Min Chart, DEMA (20) > DEMA (40)
 * - Trade the ATM Option
 * - Trigger: Buy Call/Put Option on Swing Breakout in 3% Option Chart when Option DEMA 20 > 40 and Spot Donchian Channel (40) triggers
 * - Stop Loss: 1 Brick below Swing Low in Option Chart
 */
object Strategy10OptionBuyingDcIntraday {

    val spotBoxSizePercent: Double = 0.075 // PDF Page 188 & 190
    val optionBoxSizePercent: Double = 3.0  // PDF Page 189 & 191

    fun evaluate(
        optionSymbol: String,
        optionBricks: List<RenkoBrick>,
        isSpotDonchianChannelTriggered: Boolean
    ): List<RenkoTradeSignal> {
        val signals = mutableListOf<RenkoTradeSignal>()
        if (!isSpotDonchianChannelTriggered || optionBricks.size < 40) return signals

        val dema20 = RenkoIndicators.calculateDEMA(optionBricks, 20)
        val dema40 = RenkoIndicators.calculateDEMA(optionBricks, 40)
        val patterns = RenkoPatternDetector.detectPatterns(optionBricks)

        for (pattern in patterns) {
            val idx = pattern.brickIndex
            val d20 = dema20[idx] ?: continue
            val d40 = dema40[idx] ?: continue
            val brick = optionBricks[idx]

            // Buy Option on Swing Breakout when DEMA 20 > DEMA 40
            if (d20 > d40 && pattern.patternType == RenkoPatternType.BULLISH_SWING_BREAKOUT) {
                val target = RenkoTargetCalculator.calculateSwingExtensionTarget(
                    swingHighOrLowPrice = pattern.price,
                    brickCount = 3,
                    brickValue = brick.brickSize,
                    isBullish = true
                )
                signals.add(
                    RenkoTradeSignal(
                        action = TradeAction.BUY,
                        strategyName = "Strategy 10: Option Buying DC + DEMA",
                        symbol = optionSymbol,
                        entryPrice = pattern.price,
                        stopLoss = pattern.stopLoss,
                        target = target,
                        timestamp = pattern.timestamp,
                        reason = "Spot DC(40) Trigger + Option DEMA 20 > 40 + Swing Breakout in 3% Option Chart"
                    )
                )
            }
        }

        return signals
    }
}
