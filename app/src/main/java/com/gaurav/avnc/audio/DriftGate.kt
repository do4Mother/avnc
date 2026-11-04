/*
 * SPDX-License-Identifier:  GPL-3.0-or-later
 */

package com.gaurav.avnc.audio

const val TARGET_MS = 80
const val HIGH_MS = 160

sealed interface Decision {
    object Write : Decision
    object Drop : Decision
    data class Prefill(val silenceMs: Int) : Decision
}

/**
 * Keeps the amount of audio buffered in the output device near [targetMs].
 * Pure logic (no Android types) so it can be unit tested on the JVM.
 *
 * - Empty buffer (underrun): prefill silence up to target, then write.
 * - Above [highMs]: drop chunks until buffered audio is back at or below [targetMs].
 * - Otherwise: write.
 */
class DriftGate(val targetMs: Int = TARGET_MS, val highMs: Int = HIGH_MS) {

    private var dropping = false

    fun decide(bufferedMs: Int): Decision {
        if (bufferedMs <= 0) {
            dropping = false
            return Decision.Prefill(targetMs)
        }
        if (dropping) {
            if (bufferedMs <= targetMs) dropping = false
        } else if (bufferedMs > highMs) {
            dropping = true
        }
        return if (dropping) Decision.Drop else Decision.Write
    }
}
