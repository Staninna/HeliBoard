// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import helium314.keyboard.fork.net.NetworkGate
import helium314.keyboard.fork.net.NetworkGate.Feature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NetworkGateTest {
    @Test fun refusesWhenTheFeatureIsOff() {
        assertFailsWith<NetworkGate.Refused> { NetworkGate.check(Feature.LOCAL_AI, "http://127.0.0.1:8080/health", false) }
        assertFailsWith<NetworkGate.Refused> { NetworkGate.check(Feature.TRANSLATE, "https://translate.example/translate", false) }
        assertFailsWith<NetworkGate.Refused> { NetworkGate.check(Feature.GIFS, "https://api.example/gifs", false) }
    }

    @Test fun localAiOnlyTalksToLoopback() {
        assertEquals("127.0.0.1", NetworkGate.check(Feature.LOCAL_AI, "http://127.0.0.1:8080/health", true).host)
        for (url in listOf(
            "http://localhost:8080/health", // a name can resolve anywhere
            "http://192.168.1.2:8080/health",
            "https://127.0.0.1:8080/health",
            "http://127.0.0.1.example.com/health",
            "http://user:pw@127.0.0.1:8080/health",
        )) assertFailsWith<NetworkGate.Refused>(url) { NetworkGate.check(Feature.LOCAL_AI, url, true) }
    }

    @Test fun otherFeaturesNeedHttps() {
        NetworkGate.check(Feature.TRANSLATE, "https://translate.example/translate", true)
        assertFailsWith<NetworkGate.Refused> { NetworkGate.check(Feature.TRANSLATE, "http://translate.example/translate", true) }
        assertFailsWith<NetworkGate.Refused> { NetworkGate.check(Feature.GIFS, "http://127.0.0.1/gifs", true) }
        assertFailsWith<NetworkGate.Refused> { NetworkGate.check(Feature.GIFS, "not a url", true) }
    }

    @Test fun gifsOnlyGoToKlipy() {
        NetworkGate.check(Feature.GIFS, "https://api.klipy.com/api/v1/key/gifs/search?q=hi", true)
        NetworkGate.check(Feature.GIFS, "https://static.klipy.com/ii/abc/14/af/um0", true)
        for (url in listOf(
            "https://klipy.com.example/gifs",
            "https://evilklipy.com/gifs",
            "https://media.giphy.com/x.gif",
        )) assertFailsWith<NetworkGate.Refused>(url) { NetworkGate.check(Feature.GIFS, url, true) }
    }
}
