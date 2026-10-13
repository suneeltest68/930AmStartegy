package com.example.renko.engine

import com.example.Candle
import com.example.renko.model.RenkoBrick
import com.example.renko.model.RenkoDirection
import com.example.renko.model.RenkoType
import kotlin.math.abs
import kotlin.math.max

/**
 * Renko Chart Construction Engine implementing classic Renko brick calculation rules
 * from Mukta Dhamankar's Renko Charts guide.
 */
class RenkoChartEngine(
    val renkoType: RenkoType = RenkoType.PERCENTAGE,
    val value: Double = 0.05, // 0.05% for Nifty intraday, 3.0% for Options, or 10.0 absolute points
    val useDynamicPercentage: Boolean = false // If true, recalculates brick size on every brick based on percentage of current price
) {

    /**
     * Converts standard timeframe candles (e.g. 1-min OHLC) into a list of Renko Bricks.
     */
    fun buildBricks(candles: List<Candle>): List<RenkoBrick> {
        if (candles.isEmpty()) return emptyList()

        val bricks = mutableListOf<RenkoBrick>()
        val initialCandle = candles.first()
        val currentPrice = initialCandle.open

        val initialBrickSize = calculateBrickSize(currentPrice)
        var lastClose = currentPrice
        var currentDirection: RenkoDirection? = null

        for (candle in candles) {
            val high = candle.high
            val low = candle.low
            val close = candle.close
            val timestamp = candle.timestamp
            val volume = candle.volume
            val epoch = candle.epoch

            // Simulate tick progression within candle: Open -> High/Low -> Close
            val pricePoints = listOf(candle.open, high, low, close)

            for (price in pricePoints) {
                while (true) {
                    val activeBrickSize = if (useDynamicPercentage && renkoType == RenkoType.PERCENTAGE) {
                        calculateBrickSize(lastClose)
                    } else {
                        initialBrickSize
                    }

                    if (currentDirection == null) {
                        // Initial brick formation
                        val diff = price - lastClose
                        if (abs(diff) >= activeBrickSize) {
                            val dir = if (diff > 0) RenkoDirection.BULLISH else RenkoDirection.BEARISH
                            val brickOpen = lastClose
                            val brickClose = if (dir == RenkoDirection.BULLISH) brickOpen + activeBrickSize else brickOpen - activeBrickSize
                            val brickHigh = max(brickOpen, brickClose)
                            val brickLow = minOf(brickOpen, brickClose)

                            val brick = RenkoBrick(
                                index = bricks.size,
                                direction = dir,
                                open = brickOpen,
                                high = brickHigh,
                                low = brickLow,
                                close = brickClose,
                                brickSize = activeBrickSize,
                                timestamp = timestamp,
                                volume = volume,
                                epoch = epoch
                            )
                            bricks.add(brick)
                            lastClose = brickClose
                            currentDirection = dir
                        } else {
                            break
                        }
                    } else if (currentDirection == RenkoDirection.BULLISH) {
                        // Continuation Bullish
                        if (price >= lastClose + activeBrickSize) {
                            val brickOpen = lastClose
                            val brickClose = brickOpen + activeBrickSize
                            val brick = RenkoBrick(
                                index = bricks.size,
                                direction = RenkoDirection.BULLISH,
                                open = brickOpen,
                                high = brickClose,
                                low = brickOpen,
                                close = brickClose,
                                brickSize = activeBrickSize,
                                timestamp = timestamp,
                                volume = volume,
                                epoch = epoch
                            )
                            bricks.add(brick)
                            lastClose = brickClose
                        }
                        // Reversal Bearish (Requires price to move 2x brickSize below previous close, i.e., 1x brickSize below previous open)
                        else if (price <= lastClose - (2 * activeBrickSize)) {
                            val brickOpen = lastClose - activeBrickSize // Reversal open is at previous open
                            val brickClose = brickOpen - activeBrickSize
                            val brick = RenkoBrick(
                                index = bricks.size,
                                direction = RenkoDirection.BEARISH,
                                open = brickOpen,
                                high = brickOpen,
                                low = brickClose,
                                close = brickClose,
                                brickSize = activeBrickSize,
                                timestamp = timestamp,
                                volume = volume,
                                epoch = epoch
                            )
                            bricks.add(brick)
                            lastClose = brickClose
                            currentDirection = RenkoDirection.BEARISH
                        } else {
                            break
                        }
                    } else { // Current Direction == RenkoDirection.BEARISH
                        // Continuation Bearish
                        if (price <= lastClose - activeBrickSize) {
                            val brickOpen = lastClose
                            val brickClose = brickOpen - activeBrickSize
                            val brick = RenkoBrick(
                                index = bricks.size,
                                direction = RenkoDirection.BEARISH,
                                open = brickOpen,
                                high = brickOpen,
                                low = brickClose,
                                close = brickClose,
                                brickSize = activeBrickSize,
                                timestamp = timestamp,
                                volume = volume,
                                epoch = epoch
                            )
                            bricks.add(brick)
                            lastClose = brickClose
                        }
                        // Reversal Bullish (Requires price to move 2x brickSize above previous close)
                        else if (price >= lastClose + (2 * activeBrickSize)) {
                            val brickOpen = lastClose + activeBrickSize // Reversal open is at previous open
                            val brickClose = brickOpen + activeBrickSize
                            val brick = RenkoBrick(
                                index = bricks.size,
                                direction = RenkoDirection.BULLISH,
                                open = brickOpen,
                                high = brickClose,
                                low = brickOpen,
                                close = brickClose,
                                brickSize = activeBrickSize,
                                timestamp = timestamp,
                                volume = volume,
                                epoch = epoch
                            )
                            bricks.add(brick)
                            lastClose = brickClose
                            currentDirection = RenkoDirection.BULLISH
                        } else {
                            break
                        }
                    }
                }
            }
        }

        return bricks
    }

    /**
     * Calculates the brick size based on the configured RenkoType.
     */
    fun calculateBrickSize(referencePrice: Double): Double {
        return when (renkoType) {
            RenkoType.ABSOLUTE -> value
            RenkoType.PERCENTAGE -> (referencePrice * (value / 100.0))
            RenkoType.ATR -> value // ATR value passed in
        }
    }
}
