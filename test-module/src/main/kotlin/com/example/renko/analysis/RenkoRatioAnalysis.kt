package com.example.renko.analysis

import com.example.Candle
import com.example.renko.indicator.RenkoIndicators
import com.example.renko.model.RenkoBrick

enum class RelativeStrengthStatus {
    OUTPERFORMING,
    UNDERPERFORMING,
    NEUTRAL
}

data class RatioPoint(
    val timestamp: String,
    val ratioValue: Double,
    val sma30: Double?,
    val status: RelativeStrengthStatus
)

object RenkoRatioAnalysis {

    /**
     * Constructs a Ratio Candle series by dividing numerator candle close prices by denominator candle close prices.
     */
    fun calculateRatioCandles(
        numeratorCandles: List<Candle>,
        denominatorCandles: List<Candle>
    ): List<Candle> {
        val denMap = denominatorCandles.associateBy { it.timestamp }
        val result = mutableListOf<Candle>()

        for (numCandle in numeratorCandles) {
            val denCandle = denMap[numCandle.timestamp] ?: continue
            if (denCandle.close == 0.0) continue

            val ratioOpen = numCandle.open / denCandle.open
            val ratioHigh = numCandle.high / denCandle.low
            val ratioLow = numCandle.low / denCandle.high
            val ratioClose = numCandle.close / denCandle.close

            result.add(
                Candle(
                    timestamp = numCandle.timestamp,
                    open = ratioOpen,
                    high = ratioHigh,
                    low = ratioLow,
                    close = ratioClose,
                    volume = numCandle.volume,
                    epoch = numCandle.epoch
                )
            )
        }

        return result
    }

    /**
     * Analyzes Relative Strength on a Ratio Chart with 30 SMA as specified on Pages 220-229 of Renko.pdf.
     */
    fun analyzeRelativeStrength(
        ratioBricks: List<RenkoBrick>,
        smaPeriod: Int = 30
    ): List<RatioPoint> {
        val sma = RenkoIndicators.calculateSMA(ratioBricks, smaPeriod)
        val points = mutableListOf<RatioPoint>()

        for (i in ratioBricks.indices) {
            val brick = ratioBricks[i]
            val smaVal = sma[i]

            val status = if (smaVal == null) {
                RelativeStrengthStatus.NEUTRAL
            } else if (brick.close > smaVal) {
                RelativeStrengthStatus.OUTPERFORMING
            } else if (brick.close < smaVal) {
                RelativeStrengthStatus.UNDERPERFORMING
            } else {
                RelativeStrengthStatus.NEUTRAL
            }

            points.add(
                RatioPoint(
                    timestamp = brick.timestamp,
                    ratioValue = brick.close,
                    sma30 = smaVal,
                    status = status
                )
            )
        }

        return points
    }
}
