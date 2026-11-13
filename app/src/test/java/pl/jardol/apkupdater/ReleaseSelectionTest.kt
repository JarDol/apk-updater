package pl.jardol.apkupdater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSelectionTest {

    @Test
    fun onlyMatchingNonDraftReleasesWithApksAreSelectable() {
        assertTrue(Catalog.isSelectableRelease("e2stream-0.18.31", "e2stream-", false, true))
        assertFalse(Catalog.isSelectableRelease("other-0.18.31", "e2stream-", false, true))
        assertFalse(Catalog.isSelectableRelease("e2stream-0.18.31", "e2stream-", true, true))
        assertFalse(Catalog.isSelectableRelease("e2stream-0.18.31", "e2stream-", false, false))
    }

    @Test
    fun releasesAreSortedByVersionIncludingPrereleases() {
        val releases = listOf(app("0.18.29"), app("0.19.0-beta.1"), app("0.18.31"), app("0.19.0"))

        assertEquals(
            listOf("0.19.0", "0.19.0-beta.1", "0.18.31", "0.18.29"),
            Catalog.sortReleases(releases).map { it.latest },
        )
    }

    @Test
    fun downgradeDetectionIgnoresUnknownOrEqualVersions() {
        assertTrue(Installer.isDowngrade(148, 146))
        assertFalse(Installer.isDowngrade(148, 148))
        assertFalse(Installer.isDowngrade(-1, 146))
    }

    private fun app(version: String) = AppItem(
        repo = "JarDol/enigma2-epgscan",
        name = "E2Stream",
        pkg = "pl.jardol.e2stream",
        latest = version,
        apkName = "e2stream-$version.apk",
        apkUrl = "https://example.invalid/e2stream-$version.apk",
        apkApiUrl = null,
        notes = "",
        error = null,
        prerelease = version.contains('-'),
    )
}
