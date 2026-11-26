// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

/** Fork: preference keys of the fork's own settings. */
object ForkSettings {
    /** Show the voice input key. Off for people who dictate with another app, like a floating bubble. */
    const val PREF_VOICE_KEY = "fork_voice_key"
    const val DEFAULT_VOICE_KEY = true

    /** Offer the proofread chip after typing (only while paired with SmolChat Server). */
    const val PREF_PROOFREAD_CHIP = "fork_proofread_chip"
    const val DEFAULT_PROOFREAD_CHIP = true

    /** Notify when a rewrite finishes while the keyboard is hidden. Needs the notification permission. */
    const val PREF_REWRITE_NOTIFY = "fork_rewrite_notify"
    const val DEFAULT_REWRITE_NOTIFY = true

    /** Offer a recent screenshot in the empty suggestion bar. Needs the photo permission. */
    const val PREF_SCREENSHOT_SUGGESTION = "fork_screenshot_suggestion"
    const val DEFAULT_SCREENSHOT_SUGGESTION = false
}
