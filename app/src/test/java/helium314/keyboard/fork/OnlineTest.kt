// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import helium314.keyboard.fork.online.Klipy
import helium314.keyboard.fork.online.LibreTranslate
import helium314.keyboard.fork.online.OnlineSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnlineTest {
    @Test fun readsLibreTranslateLanguagesAndTranslations() {
        val languages = LibreTranslate.parseLanguages(
            """[{"code":"en","name":"English","targets":["de","nl"]},{"code":"nl","name":"Dutch","targets":["en"]}]"""
        )
        assertEquals(listOf("en", "nl"), languages.map { it.code })
        val t = LibreTranslate.parseTranslation("""{"detectedLanguage":{"confidence":90,"language":"nl"},"translatedText":"Hello"}""")
        assertEquals("Hello", t.text)
        assertEquals("nl", t.detected)
        // a fixed source language comes back without detection
        assertEquals(null, LibreTranslate.parseTranslation("""{"translatedText":"Hallo"}""").detected)
        assertFailsWith<LibreTranslate.Failure> { LibreTranslate.parseLanguages("<html>not a server</html>") }
    }

    @Test fun libreTranslateErrorsBecomeOneProblemEach() {
        assertEquals(LibreTranslate.Problem.KEY,
            LibreTranslate.failureFor(400, """{"error":"Visit https://portal.libretranslate.com to get an API key"}""").problem)
        assertEquals(LibreTranslate.Problem.KEY, LibreTranslate.failureFor(403, """{"error":"Invalid API key"}""").problem)
        assertEquals(LibreTranslate.Problem.BUSY, LibreTranslate.failureFor(429, """{"error":"Slowdown"}""").problem)
        assertEquals(LibreTranslate.Problem.TOO_LONG,
            LibreTranslate.failureFor(400, """{"error":"Invalid request: request (6000) exceeds text limit (5000)"}""").problem)
        assertEquals(LibreTranslate.Problem.FAILED, LibreTranslate.failureFor(500, null).problem)
    }

    @Test fun recentTargetsComeFirstThenByName() {
        val languages = listOf(
            LibreTranslate.Language("de", "German"), LibreTranslate.Language("en", "English"),
            LibreTranslate.Language("nl", "Dutch"), LibreTranslate.Language("fr", "French"),
        )
        assertEquals(listOf("nl", "fr", "en", "de"),
            OnlineSettings.targetOrder(languages, listOf("nl", "xx", "fr")).map { it.code })
    }

    private fun gif(slug: String, type: String = "gif", mdSize: Long = 900_000) = """
        {"id":1,"slug":"$slug","title":"Hello","type":"$type","file":{
          "md":{"gif":{"url":"https://static.klipy.com/md.gif","width":498,"height":300,"size":$mdSize}},
          "sm":{"gif":{"url":"https://static.klipy.com/sm.gif","width":220,"height":132,"size":300000},
                "webp":{"url":"https://static.klipy.com/sm.webp","width":220,"height":132,"size":80000}}}}
    """

    @Test fun readsKlipyPagesWithoutAds() {
        val page = Klipy.parsePage("""{"result":true,"data":{"data":[${gif("hello-1")},${gif("ad-1", type = "ad")},${gif("big", mdSize = 9_000_000)}],
            "current_page":1,"per_page":24,"has_next":true}}""")
        assertEquals(listOf("hello-1", "big"), page.gifs.map { it.slug })
        assertTrue(page.hasNext)
        val hello = page.gifs[0]
        assertEquals("https://static.klipy.com/sm.webp", hello.preview.url) // small animated preview for the grid
        assertEquals("https://static.klipy.com/md.gif", hello.send.url)
        assertEquals("https://static.klipy.com/sm.gif", page.gifs[1].send.url) // too big to send: the smaller one
        assertFalse(Klipy.parsePage("""{"result":true,"data":{"data":[],"has_next":false}}""").hasNext)
    }

    @Test fun klipyErrors() {
        assertEquals(Klipy.Problem.KEY, Klipy.failureFor(404, """{"result":false,"errors":{"message":["The provided API key is invalid."]}}""").problem)
        assertEquals(Klipy.Problem.BUSY, Klipy.failureFor(429, null).problem)
        assertEquals(Klipy.Problem.FAILED, Klipy.failureFor(500, "oops").problem)
    }
}
