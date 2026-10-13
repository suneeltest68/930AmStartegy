package com.example.renko.indicator

import com.example.renko.model.*
import kotlin.math.abs
import kotlin.math.max

object RenkoIndicators {

    /**
     * Calculates Simple Moving Average (SMA) over Renko brick close prices.
     */
    fun calculateSMA(bricks: List<RenkoBrick>, period: Int): List<Double?> {
        val result = mutableListOf<Double?>()
        var sum = 0.0

        for (i in bricks.indices) {
            sum += bricks[i].close
            if (i >= period) {
                sum -= bricks[i - period].close
            }
            if (i >= period - 1) {
                result.add(sum / period)
            } else {
                result.add(null)
            }
        }
        return result
    }

    /**
     * Calculates Exponential Moving Average (EMA) over Renko brick close prices.
     */
    fun calculateEMA(bricks: List<RenkoBrick>, period: Int): List<Double?> {
        val prices = bricks.map { it.close }
        return calculateEMAValues(prices, period)
    }

    private fun calculateEMAValues(prices: List<Double>, period: Int): List<Double?> {
        val result = mutableListOf<Double?>()
        if (prices.size < period) {
            return List(prices.size) { null }
        }

        val multiplier = 2.0 / (period + 1)
        var prevEma = prices.take(period).average()

        for (i in prices.indices) {
            if (i < period - 1) {
                result.add(null)
            } else if (i == period - 1) {
                result.add(prevEma)
            } else {
                val currentEma = (prices[i] - prevEma) * multiplier + prevEma
                result.add(currentEma)
                prevEma = currentEma
            }
        }
        return result
    }

    /**
     * Calculates Double Exponential Moving Average (DEMA).
     * Formula: DEMA = 2 * EMA(n) - EMA(EMA(n))
     */
    fun calculateDEMA(bricks: List<RenkoBrick>, period: Int): List<Double?> {
        val prices = bricks.map { it.close }
        val ema1 = calculateEMAValues(prices, period)
        val nonNullEma1Prices = mutableListOf<Double>()
        val ema1Indices = mutableListOf<Int>()

        for (i in ema1.indices) {
            ema1[i]?.let {
                nonNullEma1Prices.add(it)
                ema1Indices.add(i)
            }
        }

        val ema2Values = calculateEMAValues(nonNullEma1Prices, period)
        val result = MutableList<Double?>(prices.size) { null }

        for (j in ema2Values.indices) {
            val ema2 = ema2Values[j] ?: continue
            val originalIndex = ema1Indices[j]
            val e1 = ema1[originalIndex] ?: continue
            result[originalIndex] = (2 * e1) - ema2
        }

        return result
    }

    /**
     * Calculates Triple Exponential Moving Average (TEMA).
     * Formula: TEMA = 3 * EMA1 - 3 * EMA2 + EMA3
     */
    fun calculateTEMA(bricks: List<RenkoBrick>, period: Int): List<Double?> {
        val prices = bricks.map { it.close }
        val ema1 = calculateEMAValues(prices, period)

        val ema1Values = mutableListOf<Double>()
        val ema1Map = mutableMapOf<Int, Double>()
        val ema1Indices = mutableListOf<Int>()
        for (i in ema1.indices) {
            ema1[i]?.let {
                ema1Values.add(it)
                ema1Map[i] = it
                ema1Indices.add(i)
            }
        }

        val ema2 = calculateEMAValues(ema1Values, period)
        val ema2Values = mutableListOf<Double>()
        val ema2Map = mutableMapOf<Int, Double>() // originalIndex -> ema2
        val ema2Indices = mutableListOf<Int>()

        for (j in ema2.indices) {
            ema2[j]?.let {
                val origIdx = ema1Indices[j]
                ema2Values.add(it)
                ema2Map[origIdx] = it
                ema2Indices.add(origIdx)
            }
        }

        val ema3 = calculateEMAValues(ema2Values, period)
        val result = MutableList<Double?>(prices.size) { null }

        for (k in ema3.indices) {
            val e3 = ema3[k] ?: continue
            val origIdx = ema2Indices[k]
            val e1 = ema1Map[origIdx] ?: continue
            val e2 = ema2Map[origIdx] ?: continue
            result[origIdx] = (3 * e1) - (3 * e2) + e3
        }

        return result
    }

    /**
     * Calculates SuperTrend indicator on Renko Bricks.
     * @param period Lookback period (e.g., 40)
     * @param multiplier ATR multiplier (e.g., 14.0 or 12.0)
     */
    fun calculateSuperTrend(
        bricks: List<RenkoBrick>,
        period: Int = 40,
        multiplier: Double = 12.0
    ): List<SuperTrendValue?> {
        if (bricks.size < period) return List(bricks.size) { null }

        // True Range calculation
        val trList = mutableListOf<Double>()
        for (i in bricks.indices) {
            if (i == 0) {
                trList.add(bricks[i].high - bricks[i].low)
            } else {
                val high = bricks[i].high
                val low = bricks[i].low
                val prevClose = bricks[i - 1].close
                val tr = max(high - low, max(abs(high - prevClose), abs(low - prevClose)))
                trList.add(tr)
            }
        }

        // ATR using Wilder's Smoothing
        val atrList = MutableList<Double?>(bricks.size) { null }
        val atrSum = trList.take(period).sum()
        var currentAtr = atrSum / period
        atrList[period - 1] = currentAtr

        for (i in period until bricks.size) {
            currentAtr = (currentAtr * (period - 1) + trList[i]) / period
            atrList[i] = currentAtr
        }

        val result = MutableList<SuperTrendValue?>(bricks.size) { null }
        var prevUpperBand = 0.0
        var prevLowerBand = 0.0
        var prevSuperTrend = 0.0
        var isBullish = true

        for (i in period - 1 until bricks.size) {
            val brick = bricks[i]
            val atr = atrList[i] ?: continue
            val hl2 = (brick.high + brick.low) / 2.0

            var basicUpperBand = hl2 + (multiplier * atr)
            var basicLowerBand = hl2 - (multiplier * atr)

            val prevBrickClose = if (i > 0) bricks[i - 1].close else brick.close

            var finalUpperBand = if (basicUpperBand < prevUpperBand || prevBrickClose > prevUpperBand) basicUpperBand else prevUpperBand
            var finalLowerBand = if (basicLowerBand > prevLowerBand || prevBrickClose < prevLowerBand) basicLowerBand else prevLowerBand

            if (i == period - 1) {
                finalUpperBand = basicUpperBand
                finalLowerBand = basicLowerBand
                isBullish = brick.close >= finalLowerBand
            } else {
                if (prevSuperTrend == prevUpperBand) {
                    isBullish = brick.close > finalUpperBand
                } else {
                    isBullish = brick.close >= finalLowerBand
                }
            }

            val currentSuperTrend = if (isBullish) finalLowerBand else finalUpperBand

            result[i] = SuperTrendValue(currentSuperTrend, isBullish)

            prevUpperBand = finalUpperBand
            prevLowerBand = finalLowerBand
            prevSuperTrend = currentSuperTrend
        }

        return result
    }

    /**
     * Calculates Donchian Channel over Renko Bricks.
     */
    fun calculateDonchianChannel(bricks: List<RenkoBrick>, period: Int = 40): List<DonchianChannelValue?> {
        val result = mutableListOf<DonchianChannelValue?>()

        for (i in bricks.indices) {
            if (i < period - 1) {
                result.add(null)
            } else {
                val window = bricks.subList(i - period + 1, i + 1)
                val upperBand = window.maxOf { it.high }
                val lowerBand = window.minOf { it.low }
                val middleBand = (upperBand + lowerBand) / 2.0
                result.add(DonchianChannelValue(upperBand, middleBand, lowerBand))
            }
        }
        return result
    }

    /**
     * Calculates Relative Strength Index (RSI) on Renko Bricks.
     */
    fun calculateRSI(bricks: List<RenkoBrick>, period: Int = 14): List<Double?> {
        val result = MutableList<Double?>(bricks.size) { null }
        if (bricks.size <= period) return result

        var gainSum = 0.0
        var lossSum = 0.0

        for (i in 1..period) {
            val change = bricks[i].close - bricks[i - 1].close
            if (change > 0) gainSum += change else lossSum += abs(change)
        }

        var avgGain = gainSum / period
        var avgLoss = lossSum / period

        val firstRs = if (avgLoss == 0.0) 100.0 else avgGain / avgLoss
        result[period] = if (avgLoss == 0.0) 100.0 else 100.0 - (100.0 / (1.0 + firstRs))

        for (i in period + 1 until bricks.size) {
            val change = bricks[i].close - bricks[i - 1].close
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) abs(change) else 0.0

            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period

            if (avgLoss == 0.0) {
                result[i] = 100.0
            } else {
                val rs = avgGain / avgLoss
                result[i] = 100.0 - (100.0 / (1.0 + rs))
            }
        }

        return result
    }

    /**
     * Calculates Adaptive RSI (ARSI 14, 9) on Renko Bricks.
     */
    fun calculateAdaptiveRSI(bricks: List<RenkoBrick>, rsiPeriod: Int = 14, smoothPeriod: Int = 9): List<Double?> {
        val rawRsi = calculateRSI(bricks, rsiPeriod)
        val nonNullRsi = rawRsi.filterNotNull()
        if (nonNullRsi.isEmpty()) return List(bricks.size) { null }

        // Apply EMA smoothing over raw RSI
        val smoothedRsi = calculateEMAValues(nonNullRsi, smoothPeriod)
        val result = MutableList<Double?>(bricks.size) { null }

        var rsiIdx = 0
        for (i in rawRsi.indices) {
            if (rawRsi[i] != null) {
                if (rsiIdx < smoothedRsi.size) {
                    result[i] = smoothedRsi[rsiIdx]
                }
                rsiIdx++
            }
        }

        return result
    }

    /**
     * Calculates MACD (12, 26, 9) on Renko Bricks.
     */
    fun calculateMACD(
        bricks: List<RenkoBrick>,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9
    ): List<MACDValue?> {
        val fastEma = calculateEMA(bricks, fastPeriod)
        val slowEma = calculateEMA(bricks, slowPeriod)

        val macdLine = MutableList<Double?>(bricks.size) { null }
        val nonNullMacd = mutableListOf<Double>()
        val macdIndices = mutableListOf<Int>()

        for (i in bricks.indices) {
            val fast = fastEma[i]
            val slow = slowEma[i]
            if (fast != null && slow != null) {
                val lineVal = fast - slow
                macdLine[i] = lineVal
                nonNullMacd.add(lineVal)
                macdIndices.add(i)
            }
        }

        val signalLineValues = calculateEMAValues(nonNullMacd, signalPeriod)
        val result = MutableList<MACDValue?>(bricks.size) { null }

        for (j in signalLineValues.indices) {
            val sig = signalLineValues[j] ?: continue
            val origIdx = macdIndices[j]
            val macdVal = macdLine[origIdx] ?: continue
            val hist = macdVal - sig
            result[origIdx] = MACDValue(macdVal, sig, hist)
        }

        return result
    }

    /**
     * Calculates ADX / DMI (+DI, -DI) on Renko Bricks.
     */
    fun calculateADX(bricks: List<RenkoBrick>, period: Int = 14): List<ADXValue?> {
        val result = MutableList<ADXValue?>(bricks.size) { null }
        if (bricks.size <= period * 2) return result

        val plusDM = mutableListOf<Double>()
        val minusDM = mutableListOf<Double>()
        val trList = mutableListOf<Double>()

        for (i in bricks.indices) {
            if (i == 0) {
                plusDM.add(0.0)
                minusDM.add(0.0)
                trList.add(bricks[0].high - bricks[0].low)
            } else {
                val upMove = bricks[i].high - bricks[i - 1].high
                val downMove = bricks[i - 1].low - bricks[i].low

                val pdm = if (upMove > downMove && upMove > 0) upMove else 0.0
                val mdm = if (downMove > upMove && downMove > 0) downMove else 0.0

                plusDM.add(pdm)
                minusDM.add(mdm)

                val tr = max(
                    bricks[i].high - bricks[i].low,
                    max(abs(bricks[i].high - bricks[i - 1].close), abs(bricks[i].low - bricks[i - 1].close))
                )
                trList.add(tr)
            }
        }

        var smoothedTR = trList.take(period).sum()
        var smoothedPlusDM = plusDM.take(period).sum()
        var smoothedMinusDM = minusDM.take(period).sum()

        val dxList = MutableList<Double?>(bricks.size) { null }
        val plusDIList = MutableList<Double?>(bricks.size) { null }
        val minusDIList = MutableList<Double?>(bricks.size) { null }

        for (i in period until bricks.size) {
            if (i > period) {
                smoothedTR = smoothedTR - (smoothedTR / period) + trList[i]
                smoothedPlusDM = smoothedPlusDM - (smoothedPlusDM / period) + plusDM[i]
                smoothedMinusDM = smoothedMinusDM - (smoothedMinusDM / period) + minusDM[i]
            }

            val pDI = if (smoothedTR == 0.0) 0.0 else (smoothedPlusDM / smoothedTR) * 100.0
            val mDI = if (smoothedTR == 0.0) 0.0 else (smoothedMinusDM / smoothedTR) * 100.0

            plusDIList[i] = pDI
            minusDIList[i] = mDI

            val diDiff = abs(pDI - mDI)
            val diSum = pDI + mDI
            dxList[i] = if (diSum == 0.0) 0.0 else (diDiff / diSum) * 100.0
        }

        val nonNullDx = dxList.filterNotNull()
        if (nonNullDx.size >= period) {
            var adx = nonNullDx.take(period).average()
            val dxIndices = dxList.mapIndexedNotNull { index, value -> if (value != null) index else null }

            val firstAdxIdx = dxIndices[period - 1]
            result[firstAdxIdx] = ADXValue(adx, plusDIList[firstAdxIdx]!!, minusDIList[firstAdxIdx]!!)

            for (j in period until nonNullDx.size) {
                val origIdx = dxIndices[j]
                adx = (adx * (period - 1) + nonNullDx[j]) / period
                val pDI = plusDIList[origIdx] ?: 0.0
                val mDI = minusDIList[origIdx] ?: 0.0
                result[origIdx] = ADXValue(adx, pDI, mDI)
            }
        }

        return result
    }
}
