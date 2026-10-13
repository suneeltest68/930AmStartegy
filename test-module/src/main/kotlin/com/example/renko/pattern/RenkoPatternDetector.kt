package com.example.renko.pattern

import com.example.renko.model.*
import kotlin.math.abs

object RenkoPatternDetector {

    /**
     * Extracts all contiguous swing moves from a sequence of Renko Bricks.
     */
    fun extractSwings(bricks: List<RenkoBrick>): List<RenkoSwing> {
        if (bricks.isEmpty()) return emptyList()

        val swings = mutableListOf<RenkoSwing>()
        var startIdx = 0
        var currentDir = bricks[0].direction

        for (i in 1 until bricks.size) {
            if (bricks[i].direction != currentDir) {
                val subBricks = bricks.subList(startIdx, i)
                val highPrice = subBricks.maxOf { it.high }
                val lowPrice = subBricks.minOf { it.low }

                swings.add(
                    RenkoSwing(
                        startIndex = startIdx,
                        endIndex = i - 1,
                        direction = currentDir,
                        brickCount = subBricks.size,
                        startPrice = subBricks.first().open,
                        endPrice = subBricks.last().close,
                        highPrice = highPrice,
                        lowPrice = lowPrice
                    )
                )
                startIdx = i
                currentDir = bricks[i].direction
            }
        }

        // Add final active swing
        val subBricks = bricks.subList(startIdx, bricks.size)
        val highPrice = subBricks.maxOf { it.high }
        val lowPrice = subBricks.minOf { it.low }
        swings.add(
            RenkoSwing(
                startIndex = startIdx,
                endIndex = bricks.size - 1,
                direction = currentDir,
                brickCount = subBricks.size,
                startPrice = subBricks.first().open,
                endPrice = subBricks.last().close,
                highPrice = highPrice,
                lowPrice = lowPrice
            )
        )

        return swings
    }

    /**
     * Scans for all Renko Patterns on the given list of bricks according to Renko.pdf rules.
     */
    fun detectPatterns(bricks: List<RenkoBrick>): List<RenkoPatternSignal> {
        val signals = mutableListOf<RenkoPatternSignal>()
        if (bricks.size < 4) return signals

        val swings = extractSwings(bricks)

        for (i in 2 until bricks.size) {
            val currBrick = bricks[i]
            val prevBrick = bricks[i - 1]

            // 1. Swing High / Swing Low Breakout
            val priorSwings = swings.filter { it.endIndex < i }
            val priorBullishSwings = priorSwings.filter { it.direction == RenkoDirection.BULLISH }
            val priorBearishSwings = priorSwings.filter { it.direction == RenkoDirection.BEARISH }

            val lastSwingHighPrice = priorBullishSwings.lastOrNull()?.highPrice
            val lastSwingLowPrice = priorBearishSwings.lastOrNull()?.lowPrice

            // Bullish Swing Breakout: Current bullish brick closes above prior Swing High
            if (currBrick.isBullish && prevBrick.isBullish.not() && lastSwingHighPrice != null) {
                if (currBrick.close > lastSwingHighPrice) {
                    val sl = (lastSwingLowPrice ?: (currBrick.open - currBrick.brickSize)) - currBrick.brickSize
                    signals.add(
                        RenkoPatternSignal(
                            patternType = RenkoPatternType.BULLISH_SWING_BREAKOUT,
                            brickIndex = i,
                            timestamp = currBrick.timestamp,
                            price = currBrick.close,
                            stopLoss = sl,
                            description = "Bullish Swing Breakout above prior Swing High ($lastSwingHighPrice)"
                        )
                    )
                }
            }

            // Bearish Swing Breakdown: Current bearish brick closes below prior Swing Low
            if (currBrick.isBearish && prevBrick.isBearish.not() && lastSwingLowPrice != null) {
                if (currBrick.close < lastSwingLowPrice) {
                    val sl = (lastSwingHighPrice ?: (currBrick.open + currBrick.brickSize)) + currBrick.brickSize
                    signals.add(
                        RenkoPatternSignal(
                            patternType = RenkoPatternType.BEARISH_SWING_BREAKDOWN,
                            brickIndex = i,
                            timestamp = currBrick.timestamp,
                            price = currBrick.close,
                            stopLoss = sl,
                            description = "Bearish Swing Breakdown below prior Swing Low ($lastSwingLowPrice)"
                        )
                    )
                }
            }

            // 2. One-Back / Two-Back Pattern
            if (i >= 3) {
                val b3 = bricks[i - 3]
                val b2 = bricks[i - 2]
                val b1 = bricks[i - 1]
                val b0 = bricks[i]

                // One-Back Bullish: Bullish brick -> 1 Bearish brick -> Bullish brick
                if (b2.isBullish && b1.isBearish && b0.isBullish) {
                    val sl = b1.low - b0.brickSize
                    signals.add(
                        RenkoPatternSignal(
                            patternType = RenkoPatternType.ONE_BACK_BULLISH,
                            brickIndex = i,
                            timestamp = b0.timestamp,
                            price = b0.close,
                            stopLoss = sl,
                            description = "One-Back Bullish Pattern (1 bearish pullback brick followed by bullish resumption)"
                        )
                    )
                }

                // One-Back Bearish: Bearish brick -> 1 Bullish brick -> Bearish brick
                if (b2.isBearish && b1.isBullish && b0.isBearish) {
                    val sl = b1.high + b0.brickSize
                    signals.add(
                        RenkoPatternSignal(
                            patternType = RenkoPatternType.ONE_BACK_BEARISH,
                            brickIndex = i,
                            timestamp = b0.timestamp,
                            price = b0.close,
                            stopLoss = sl,
                            description = "One-Back Bearish Pattern (1 bullish pullback brick followed by bearish resumption)"
                        )
                    )
                }

                // Two-Back Bullish: Bullish brick -> 2 Bearish bricks -> Bullish brick
                if (i >= 4) {
                    if (b3.isBullish && b2.isBearish && b1.isBearish && b0.isBullish) {
                        val sl = minOf(b2.low, b1.low) - b0.brickSize
                        signals.add(
                            RenkoPatternSignal(
                                patternType = RenkoPatternType.TWO_BACK_BULLISH,
                                brickIndex = i,
                                timestamp = b0.timestamp,
                                price = b0.close,
                                stopLoss = sl,
                                description = "Two-Back Bullish Pattern (2 bearish pullback bricks followed by bullish resumption)"
                            )
                        )
                    }

                    // Two-Back Bearish: Bearish brick -> 2 Bullish bricks -> Bearish brick
                    if (b3.isBearish && b2.isBullish && b1.isBullish && b0.isBearish) {
                        val sl = maxOf(b2.high, b1.high) + b0.brickSize
                        signals.add(
                            RenkoPatternSignal(
                                patternType = RenkoPatternType.TWO_BACK_BEARISH,
                                brickIndex = i,
                                timestamp = b0.timestamp,
                                price = b0.close,
                                stopLoss = sl,
                                description = "Two-Back Bearish Pattern (2 bullish pullback bricks followed by bearish resumption)"
                            )
                        )
                    }
                }
            }

            // 3. Multi-Swing Breakout / Breakdown
            if (priorBullishSwings.size >= 2 && currBrick.isBullish) {
                val last2Highs = priorBullishSwings.takeLast(2).map { it.highPrice }
                val diff = abs(last2Highs[0] - last2Highs[1])
                if (diff <= currBrick.brickSize * 0.5 && currBrick.close > maxOf(last2Highs[0], last2Highs[1])) {
                    val sl = (lastSwingLowPrice ?: (currBrick.open - currBrick.brickSize)) - currBrick.brickSize
                    signals.add(
                        RenkoPatternSignal(
                            patternType = RenkoPatternType.MULTI_SWING_BREAKOUT,
                            brickIndex = i,
                            timestamp = currBrick.timestamp,
                            price = currBrick.close,
                            stopLoss = sl,
                            description = "Multi-Swing Bullish Breakout above tested resistance level (${last2Highs[0]})"
                        )
                    )
                }
            }

            if (priorBearishSwings.size >= 2 && currBrick.isBearish) {
                val last2Lows = priorBearishSwings.takeLast(2).map { it.lowPrice }
                val diff = abs(last2Lows[0] - last2Lows[1])
                if (diff <= currBrick.brickSize * 0.5 && currBrick.close < minOf(last2Lows[0], last2Lows[1])) {
                    val sl = (lastSwingHighPrice ?: (currBrick.open + currBrick.brickSize)) + currBrick.brickSize
                    signals.add(
                        RenkoPatternSignal(
                            patternType = RenkoPatternType.MULTI_SWING_BREAKDOWN,
                            brickIndex = i,
                            timestamp = currBrick.timestamp,
                            price = currBrick.close,
                            stopLoss = sl,
                            description = "Multi-Swing Bearish Breakdown below tested support level (${last2Lows[0]})"
                        )
                    )
                }
            }

            // 4. Bull Trap & Bear Trap (Weak Breakout - <= 2 bricks after breakout before reversal)
            if (i >= 4) {
                val b3 = bricks[i - 3]
                val b2 = bricks[i - 2]
                val b1 = bricks[i - 1]
                val b0 = bricks[i]

                // Bear Trap: Swing Breakdown occurred (b3/b2), <= 2 bearish bricks, then b0 closes back above breakdown level
                if (lastSwingLowPrice != null && b3.close < lastSwingLowPrice && b0.isBullish && b0.close > lastSwingLowPrice) {
                    val sl = b1.low - b0.brickSize
                    signals.add(
                        RenkoPatternSignal(
                            patternType = RenkoPatternType.BEAR_TRAP,
                            brickIndex = i,
                            timestamp = b0.timestamp,
                            price = b0.close,
                            stopLoss = sl,
                            description = "Bear Trap (Weak Breakdown reversed immediately after <= 2 bearish bricks)"
                        )
                    )
                }

                // Bull Trap: Swing Breakout occurred (b3/b2), <= 2 bullish bricks, then b0 closes back below breakout level
                if (lastSwingHighPrice != null && b3.close > lastSwingHighPrice && b0.isBearish && b0.close < lastSwingHighPrice) {
                    val sl = b1.high + b0.brickSize
                    signals.add(
                        RenkoPatternSignal(
                            patternType = RenkoPatternType.BULL_TRAP,
                            brickIndex = i,
                            timestamp = b0.timestamp,
                            price = b0.close,
                            stopLoss = sl,
                            description = "Bull Trap (Weak Breakout reversed immediately after <= 2 bullish bricks)"
                        )
                    )
                }
            }

            // 5. Strike-Back Pattern
            if (i >= 3) {
                val b2 = bricks[i - 2]
                val b1 = bricks[i - 1]
                val b0 = bricks[i]

                if (lastSwingLowPrice != null && b2.close < lastSwingLowPrice && b0.isBullish && b0.close > lastSwingLowPrice) {
                    signals.add(
                        RenkoPatternSignal(
                            patternType = RenkoPatternType.BULLISH_STRIKE_BACK,
                            brickIndex = i,
                            timestamp = b0.timestamp,
                            price = b0.close,
                            stopLoss = b1.low - b0.brickSize,
                            description = "Bullish Strike-Back Pattern (Price struck back above breakdown level $lastSwingLowPrice)"
                        )
                    )
                }

                if (lastSwingHighPrice != null && b2.close > lastSwingHighPrice && b0.isBearish && b0.close < lastSwingHighPrice) {
                    signals.add(
                        RenkoPatternSignal(
                            patternType = RenkoPatternType.BEARISH_STRIKE_BACK,
                            brickIndex = i,
                            timestamp = b0.timestamp,
                            price = b0.close,
                            stopLoss = b1.high + b0.brickSize,
                            description = "Bearish Strike-Back Pattern (Price struck back below breakout level $lastSwingHighPrice)"
                        )
                    )
                }
            }

            // 6. Swing Engulf Pattern
            if (swings.size >= 2) {
                val lastCompletedSwing = swings[swings.size - 2]
                val currentActiveSwing = swings.last()

                if (lastCompletedSwing.direction == RenkoDirection.BEARISH && currentActiveSwing.direction == RenkoDirection.BULLISH) {
                    if (currentActiveSwing.brickCount >= lastCompletedSwing.brickCount && currentActiveSwing.highPrice >= lastCompletedSwing.highPrice) {
                        if (i == currentActiveSwing.endIndex) {
                            signals.add(
                                RenkoPatternSignal(
                                    patternType = RenkoPatternType.SWING_ENGULF_BULLISH,
                                    brickIndex = i,
                                    timestamp = currBrick.timestamp,
                                    price = currBrick.close,
                                    stopLoss = currentActiveSwing.lowPrice - currBrick.brickSize,
                                    description = "Bullish Swing Engulf (Bullish swing completely engulfed prior bearish swing)"
                                )
                            )
                        }
                    }
                }

                if (lastCompletedSwing.direction == RenkoDirection.BULLISH && currentActiveSwing.direction == RenkoDirection.BEARISH) {
                    if (currentActiveSwing.brickCount >= lastCompletedSwing.brickCount && currentActiveSwing.lowPrice <= lastCompletedSwing.lowPrice) {
                        if (i == currentActiveSwing.endIndex) {
                            signals.add(
                                RenkoPatternSignal(
                                    patternType = RenkoPatternType.SWING_ENGULF_BEARISH,
                                    brickIndex = i,
                                    timestamp = currBrick.timestamp,
                                    price = currBrick.close,
                                    stopLoss = currentActiveSwing.highPrice + currBrick.brickSize,
                                    description = "Bearish Swing Engulf (Bearish swing completely engulfed prior bullish swing)"
                                )
                            )
                        }
                    }
                }
            }
        }

        return signals
    }
}
