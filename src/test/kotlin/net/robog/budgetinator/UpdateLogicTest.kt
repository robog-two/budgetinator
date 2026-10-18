package net.robog.budgetinator

import net.robog.budgetinator.update.MiniJson
import net.robog.budgetinator.update.Semver
import net.robog.budgetinator.update.UpdateLogic
import kotlin.test.*

class SemverTest {
    private fun v(s: String) = assertNotNull(Semver.parse(s), "parse $s")

    @Test fun parsesPlainAndPrefixed() {
        assertEquals(Semver(1, 2, 3), v("1.2.3"))
        assertEquals(Semver(1, 2, 3), v("v1.2.3"))
        assertEquals(Semver(1, 0, 0, listOf("beta", "2"), "sha.5114f85"), v("1.0.0-beta.2+sha.5114f85"))
    }

    @Test fun rejectsGarbage() {
        listOf("", "dev", "1.2", "1.2.3.4", "01.2.3", "1.2.3-", "1.2.3-01", "latest", "v").forEach {
            assertNull(Semver.parse(it), it)
        }
    }

    @Test fun ordersNumericallyNotLexically() {
        assertTrue(v("1.10.0") > v("1.9.9"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertTrue(v("1.0.10") > v("1.0.2"))
    }

    @Test fun specPrecedenceChain() {
        val chain = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0",
        ).map(::v)
        chain.zipWithNext().forEach { (a, b) -> assertTrue(a < b, "$a < $b") }
    }

    @Test fun buildMetadataIgnoredForOrdering() {
        assertEquals(0, v("1.0.0+a").compareTo(v("1.0.0+b")))
    }
}

class MiniJsonTest {
    @Test fun parsesNestedStructures() {
        val o = MiniJson.parse("""{"a":[1,2.5,-3e2,true,false,null],"b":{"c":"d"}, "e" : "" }""") as Map<*, *>
        assertEquals(listOf(1L, 2.5, -300.0, true, false, null), o["a"])
        assertEquals(mapOf("c" to "d"), o["b"])
        assertEquals("", o["e"])
    }

    @Test fun decodesEscapes() {
        assertEquals("line\n\"q\" \\ / é 😀", MiniJson.parse(""""line\n\"q\" \\ \/ é 😀""""))
    }

    @Test fun rejectsMalformed() {
        listOf("{", "[1,]", "{\"a\" 1}", "01", "\"unterminated", "tru", "{} x", "[1 2]", "\"a\tb\"").forEach {
            assertFailsWith<MiniJson.ParseException>(it) { MiniJson.parse(it) }
        }
    }
}

class UpdateLogicTest {
    private val sample = """
        {
          "url": "https://api.github.com/repos/robog-two/budgetinator/releases/1",
          "html_url": "https://github.com/robog-two/budgetinator/releases/tag/v1.2.0",
          "tag_name": "v1.2.0",
          "name": "Budgetinator 1.2.0",
          "draft": false,
          "prerelease": false,
          "body": "## What's new\r\n- **Faster** export\r\n- Fixed [bug](https://x.y/1) in `RowFit`\r\n",
          "author": { "login": "robog-two", "id": 1 },
          "assets": [
            { "name": "Budgetinator-1.2.0-arm64.zip", "size": 123456, "browser_download_url": "https://github.com/dl/arm.zip" },
            { "name": "Budgetinator-1.2.0-x64.zip", "size": 223456, "browser_download_url": "https://github.com/dl/x64.zip" },
            { "name": "Budgetinator-1.2.0-arm64.dmg", "size": 9, "browser_download_url": "https://github.com/dl/arm.dmg" }
          ]
        }
    """.trimIndent()

    @Test fun parsesGithubRelease() {
        val r = assertNotNull(UpdateLogic.parseRelease(sample))
        assertEquals(Semver(1, 2, 0), r.version)
        assertEquals("Budgetinator 1.2.0", r.name)
        assertEquals(3, r.assets.size)
        assertEquals(123456L, r.assets[0].size)
        assertFalse(r.prerelease)
    }

    @Test fun parseFailuresReturnNull() {
        assertNull(UpdateLogic.parseRelease("not json"))
        assertNull(UpdateLogic.parseRelease("""{"tag_name":"nightly"}"""))
        assertNull(UpdateLogic.parseRelease("[]"))
    }

    @Test fun newerReleaseRules() {
        val r = UpdateLogic.parseRelease(sample)!!
        assertNotNull(UpdateLogic.newerRelease(r, "1.1.9"))
        assertNotNull(UpdateLogic.newerRelease(r, "1.2.0-rc.1"))
        assertNull(UpdateLogic.newerRelease(r, "1.2.0"))
        assertNull(UpdateLogic.newerRelease(r, "1.10.0"))
        assertNull(UpdateLogic.newerRelease(r, "dev"))
        assertNull(UpdateLogic.newerRelease(r, "1.0.0", skippedVersion = "1.2.0"))
        assertNull(UpdateLogic.newerRelease(r, "1.0.0", skippedVersion = "v1.2.0"))
        assertNotNull(UpdateLogic.newerRelease(r, "1.0.0", skippedVersion = "1.1.0"))
        assertNull(UpdateLogic.newerRelease(r.copy(prerelease = true), "1.0.0"))
        assertNull(UpdateLogic.newerRelease(r.copy(draft = true), "1.0.0"))
        assertNull(UpdateLogic.newerRelease(r.copy(version = Semver.parse("2.0.0-beta")!!), "1.0.0"))
        assertNull(UpdateLogic.newerRelease(null, "1.0.0"))
    }

    @Test fun archAndAssetSelection() {
        assertEquals("arm64", UpdateLogic.archSuffix("aarch64"))
        assertEquals("x64", UpdateLogic.archSuffix("x86_64"))
        assertEquals("x64", UpdateLogic.archSuffix("amd64"))
        assertNull(UpdateLogic.archSuffix("ppc64"))
        val r = UpdateLogic.parseRelease(sample)!!
        assertEquals("https://github.com/dl/arm.zip", UpdateLogic.selectAsset(r, "arm64")?.downloadUrl)
        assertEquals("https://github.com/dl/x64.zip", UpdateLogic.selectAsset(r, "x64")?.downloadUrl)
        assertEquals("https://github.com/dl/arm.dmg", UpdateLogic.selectAsset(r, "arm64", "dmg")?.downloadUrl)
        assertNull(UpdateLogic.selectAsset(r, "x64", "dmg"))
        assertNull(UpdateLogic.selectAsset(r.copy(assets = emptyList()), "arm64"))
    }

    @Test fun teamIdParsing() {
        val signed = """
            Executable=/Applications/Budgetinator.app/Contents/MacOS/Budgetinator
            Identifier=net.robog.budgetinator
            Format=app bundle with Mach-O thin (arm64)
            Authority=Developer ID Application: Sam Example (AB12CD34EF)
            TeamIdentifier=AB12CD34EF
            Sealed Resources version=2 rules=13 files=200
        """.trimIndent()
        assertEquals("AB12CD34EF", UpdateLogic.parseTeamId(signed))
        assertNull(UpdateLogic.parseTeamId("Signature=adhoc\nTeamIdentifier=not set\n"))
        assertNull(UpdateLogic.parseTeamId("code object is not signed at all"))
        assertNull(UpdateLogic.parseTeamId("TeamIdentifier=\n"))
    }

    @Test fun bundlePath() {
        assertEquals("/Applications/Budgetinator.app",
            UpdateLogic.bundlePathFrom("/Applications/Budgetinator.app/Contents/MacOS/Budgetinator"))
        assertEquals("/Users/me/Apps/Budgetinator.app",
            UpdateLogic.bundlePathFrom("/Users/me/Apps/Budgetinator.app/Contents/runtime/Contents/Home/"))
        assertNull(UpdateLogic.bundlePathFrom("/usr/lib/jvm/bin/java"))
        assertNull(UpdateLogic.bundlePathFrom(null))
    }

    @Test fun notesSnippet() {
        val r = UpdateLogic.parseRelease(sample)!!
        assertEquals("What's new\n• Faster export\n• Fixed bug in RowFit", UpdateLogic.notesSnippet(r.body))
        val long = "word ".repeat(300)
        val s = UpdateLogic.notesSnippet(long, maxChars = 100)
        assertTrue(s.length <= 101 && s.endsWith("…"), s)
    }
}
