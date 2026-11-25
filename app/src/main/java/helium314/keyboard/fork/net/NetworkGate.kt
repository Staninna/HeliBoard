// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fork: the only way the keyboard opens a network connection. Every request names the feature it
 * is for; the gate refuses it unless that feature is turned on and the address fits the feature.
 * Plain http only ever goes to 127.0.0.1.
 */
object NetworkGate {
    enum class Feature {
        /** The SmolChat server on this phone: http://127.0.0.1 only. */
        LOCAL_AI,
        /** LibreTranslate at the user's own address: https only. */
        TRANSLATE,
        /** GIF search with KLIPY: https to klipy.com only. */
        GIFS,
    }

    class Refused(message: String) : IOException(message)

    const val LOOPBACK = "127.0.0.1"

    /** KLIPY: the API and the media both live under this domain. */
    const val GIF_HOST = "klipy.com"

    /** Checks [url] for [feature] and returns it parsed, or throws [Refused]. */
    fun check(feature: Feature, url: String, enabled: Boolean): URL {
        if (!enabled) throw Refused("$feature is off")
        val parsed = try {
            URL(url)
        } catch (e: java.net.MalformedURLException) {
            throw Refused("not a URL")
        }
        val scheme = parsed.protocol.lowercase()
        val host = parsed.host.lowercase()
        if (!parsed.userInfo.isNullOrEmpty()) throw Refused("credentials in the URL")
        when (feature) {
            Feature.LOCAL_AI -> if (scheme != "http" || host != LOOPBACK) throw Refused("local AI is 127.0.0.1 only")
            Feature.TRANSLATE -> if (scheme != "https") throw Refused("$feature needs https")
            Feature.GIFS -> if (scheme != "https" || !(host == GIF_HOST || host.endsWith(".$GIF_HOST")))
                throw Refused("GIF search is https to $GIF_HOST only")
        }
        return parsed
    }

    /** Opens a connection to [url] for [feature] after [check]. */
    fun open(feature: Feature, url: String, enabled: Boolean): HttpURLConnection {
        val connection = check(feature, url, enabled).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false // a redirect could leave the checked address
        connection.useCaches = false
        return connection
    }
}
