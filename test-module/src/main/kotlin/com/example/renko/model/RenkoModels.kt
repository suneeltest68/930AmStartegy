package com.example.renko.model

enum class RenkoDirection {
    BULLISH,
    BEARISH
}

enum class RenkoType {
    ABSOLUTE,   // Fixed point size (e.g., 5.0, 10.0, 20.0)
    PERCENTAGE, // Percentage size (e.g., 0.05%, 0.075%, 0.10%, 0.15%, 0.25%, 1%, 3%)
    ATR         // Average True Range based size
}

data class RenkoBrick(
    val index: Int,
    val direction: RenkoDirection,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val brickSize: Double,
    val timestamp: String,
    val volume: Double = 0.0,
    val epoch: Long = 0L
) {
    val isBullish: Boolean get() = direction == RenkoDirection.BULLISH
    val isBearish: Boolean get() = direction == RenkoDirection.BEARISH
}

enum class RenkoPatternType {
    BULLISH_SWING_BREAKOUT,
    BEARISH_SWING_BREAKDOWN,
    BULLISH_SWING_BREAKOUT_FAILURE,
    BEARISH_SWING_BREAKDOWN_FAILURE,
    ONE_BACK_BULLISH,
    ONE_BACK_BEARISH,
    TWO_BACK_BULLISH,
    TWO_BACK_BEARISH,
    MULTI_SWING_BREAKOUT,
    MULTI_SWING_BREAKDOWN,
    ZIG_ZAG_CONSOLIDATION,
    BULL_TRAP,
    BEAR_TRAP,
    ABCD_BULLISH,
    ABCD_BEARISH,
    BULLISH_STRIKE_BACK,
    BEARISH_STRIKE_BACK,
    SWING_ENGULF_BULLISH,
    SWING_ENGULF_BEARISH
}

data class RenkoPatternSignal(
    val patternType: RenkoPatternType,
    val brickIndex: Int,
    val timestamp: String,
    val price: Double,
    val stopLoss: Double,
    val target: Double? = null,
    val description: String
)

data class RenkoSwing(
    val startIndex: Int,
    val endIndex: Int,
    val direction: RenkoDirection,
    val brickCount: Int,
    val startPrice: Double,
    val endPrice: Double,
    val highPrice: Double,
    val lowPrice: Double
)

data class SuperTrendValue(
    val value: Double,
    val isBullish: Boolean
)

data class DonchianChannelValue(
    val upperBand: Double,
    val middleBand: Double,
    val lowerBand: Double
)

data class MACDValue(
    val macd: Double,
    val signal: Double,
    val histogram: Double
)

data class ADXValue(
    val adx: Double,
    val plusDI: Double,
    val minusDI: Double
)

enum class TradeAction {
    BUY,
    SELL,
    EXIT_BUY,
    EXIT_SELL,
    NONE
}

data class RenkoTradeSignal(
    val action: TradeAction,
    val strategyName: String,
    val symbol: String,
    val entryPrice: Double,
    val stopLoss: Double,
    val target: Double? = null,
    val timestamp: String,
    val reason: String
)
