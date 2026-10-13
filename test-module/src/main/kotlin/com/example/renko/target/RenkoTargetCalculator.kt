package com.example.renko.target

/**
 * Renko Target Calculation Engine implementing price projection formulas from
 * Pages 100-105 of Mukta Dhamankar's Renko Charts guide.
 */
object RenkoTargetCalculator {

    /**
     * Calculates Swing Extension Target based on initial swing brick count.
     * Formula:
     * Potential Move = Brick Count * Brick Value
     * Target = Swing High + Potential Move (Bullish) or Swing Low - Potential Move (Bearish)
     */
    fun calculateSwingExtensionTarget(
        swingHighOrLowPrice: Double,
        brickCount: Int,
        brickValue: Double,
        isBullish: Boolean
    ): Double {
        val potentialMove = brickCount * brickValue
        return if (isBullish) {
            swingHighOrLowPrice + potentialMove
        } else {
            swingHighOrLowPrice - potentialMove
        }
    }

    /**
     * Calculates One-Back / Two-Back Pattern Target.
     * Formula:
     * Target = Breakout Level + (Pullback Brick Count * Brick Value)
     */
    fun calculateOneTwoBackTarget(
        breakoutPrice: Double,
        pullbackBrickCount: Int,
        brickValue: Double,
        isBullish: Boolean
    ): Double {
        val potentialMove = pullbackBrickCount * brickValue
        return if (isBullish) {
            breakoutPrice + potentialMove
        } else {
            breakoutPrice - potentialMove
        }
    }

    /**
     * Calculates Multi-Swing Pattern Target based on pattern height.
     * Formula:
     * Target = Projection Brick Price + (Pattern Height in Bricks * Brick Value)
     */
    fun calculateMultiSwingTarget(
        projectionPrice: Double,
        patternHeightBricks: Int,
        brickValue: Double,
        isBullish: Boolean
    ): Double {
        val patternHeight = patternHeightBricks * brickValue
        return if (isBullish) {
            projectionPrice + patternHeight
        } else {
            projectionPrice - patternHeight
        }
    }
}
