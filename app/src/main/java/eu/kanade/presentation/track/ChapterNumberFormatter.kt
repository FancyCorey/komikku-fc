package eu.kanade.presentation.track

import java.math.BigDecimal
import java.math.RoundingMode

/** Formats stored chapter progress without exposing binary floating-point noise to readers. */
internal fun formatLocalChapterNumber(value: Double): String =
    if (value.isFinite()) {
        BigDecimal.valueOf(value)
            .setScale(3, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    } else {
        value.toString()
    }
