package pl.jardol.apkupdater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourcesTest {

    @Test
    fun specAcceptsAccountRepoAndGithubUrl() {
        assertEquals("JarDol", Source.normSpec(" JarDol "))
        assertEquals("JarDol/e2stream", Source.normSpec("JarDol/e2stream"))
        assertEquals("JarDol/apk-updater", Source.normSpec("https://github.com/JarDol/apk-updater.git"))
        assertEquals("JarDol/apk-updater", Source.normSpec("https://github.com/JarDol/apk-updater/releases"))
        assertNull(Source.normSpec(""))
        assertNull(Source.normSpec("zła nazwa"))
    }

    @Test
    fun sourceShowsItsOwnChannel() {
        val a = Source("s1", "JarDol", channel = AppItem.BETA)
        val b = Source("s2", "Inny/repo")
        val items = listOf(
            app("s1", "x", "1.0", AppItem.STABLE), app("s1", "x", "1.1-beta.1", AppItem.BETA),
            app("s2", "y", "2.0", AppItem.STABLE), app("s2", "y", "2.1-beta.1", AppItem.BETA),
        )
        assertEquals(listOf("1.1-beta.1"), Catalog.forSource(items, a).map { it.latest })
        assertEquals(listOf("2.0"), Catalog.forSource(items, b).map { it.latest })
    }

    @Test
    fun trackedTakesNewestWhenSameAppInTwoSources() {
        val a = Source("s1", "JarDol")
        val b = Source("s2", "JarDol/e2stream", channel = AppItem.BETA)
        val items = listOf(
            app("s1", "e2", "0.18.45", AppItem.STABLE), app("s1", "e2", "0.18.46-beta.1", AppItem.BETA),
            app("s2", "e2", "0.18.45", AppItem.STABLE), app("s2", "e2", "0.18.46-beta.1", AppItem.BETA),
        )
        val t = Catalog.tracked(items, listOf(a, b))
        assertEquals(1, t.size)
        assertEquals("0.18.46-beta.1", t[0].latest)
        assertEquals(listOf("0.18.45"), Catalog.tracked(items, listOf(a)).map { it.latest })
    }

    private fun app(src: String, pkg: String, v: String, ch: String) = AppItem(
        repo = "JarDol/$pkg", name = pkg, pkg = pkg, latest = v, apkName = "$pkg.apk",
        apkUrl = "https://x/$pkg.apk", apkApiUrl = null, notes = "", error = null,
        channel = ch, source = src,
    )
}
