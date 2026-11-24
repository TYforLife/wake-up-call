package app.nodstop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * CLAUDE.md "Code quality", checked in the source so a rule never depends on someone remembering it.
 * Each failure message says what to do instead. Fix the cause; never loosen a check to get green.
 */
class ArchitectureTest {
    private val repo = File("..")
    private val mains = listOf("app", "engine", "brief").map { File(repo, "$it/src/main/kotlin") }
    private val app = File("src/main/kotlin/app/nodstop")

    private fun kt(dir: File) = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun rel(f: File) = f.canonicalFile.relativeTo(repo.canonicalFile).invariantSeparatorsPath
    private fun isComment(l: String) = l.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
    private fun code(f: File) = f.readLines().withIndex().filterNot { (_, l) -> isComment(l) }

    /** The comment text of a line: the whole line if it is a comment, else whatever follows `//`. */
    private fun commentText(l: String): String? = if (isComment(l)) l else l.indexOf("// ").takeIf { it >= 0 }?.let { l.substring(it) }

    @Test
    fun `only AlarmScheduler sets the system alarm (rule 1)`() {
        val users = kt(app).filter { f -> code(f).any { (_, l) -> "setAlarmClock(" in l } }.map(::rel)
        assertTrue(
            users == listOf("app/src/main/kotlin/app/nodstop/alarm/AlarmScheduler.kt"),
            "setAlarmClock in $users. Schedule through AlarmScheduler; \"ring now\" is scheduling at now (CLAUDE.md rule 1).",
        )
    }

    @Test
    fun `only the ring service makes sound (rule 1)`() {
        val audio = listOf("AudioTrack", "MediaPlayer", "SoundPool", "Ringtone", "ToneGenerator")
        val allowed = setOf("alarm/RingService.kt", "alarm/Tone.kt").map { "app/src/main/kotlin/app/nodstop/$it" }.toSet()
        val users = kt(app).filter { f -> code(f).any { (_, l) -> audio.any { it in l } } }.map(::rel).toSet()
        assertTrue(allowed.all { it in users }, "ring audio not found; did RingService move? Update this test with it.")
        assertTrue(
            (users - allowed).isEmpty(),
            "audio outside the ring path: ${users - allowed}. Never add a second way to make sound; " +
                "schedule a ring through AlarmScheduler instead (CLAUDE.md rule 1).",
        )
    }

    @Test
    fun `target SDK stays at 36 (DESIGN 5)`() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(
            Regex("""targetSdk\s*=\s*36\b""").containsMatchIn(gradle),
            "targetSdk changed. Targeting 37 can silently mute the earbuds ring in the background (Android 17 audio " +
                "hardening). Raise it only as its own PR, proven on an Android 17 phone (DESIGN §5), and update this test there.",
        )
    }

    @Test
    fun `nothing is copied off the phone (DESIGN 5)`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("android:allowBackup=\"false\"" in manifest, "allowBackup must stay false (owner: no backups).")
        assertTrue("android:dataExtractionRules=\"@xml/data_extraction_rules\"" in manifest, "the data extraction rules are not used")
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = rules.substringAfter("<$section>", "").substringBefore("</$section>")
            for (domain in listOf("root", "file", "database", "sharedpref", "external")) {
                assertTrue(
                    "<exclude domain=\"$domain\" path=\".\" />" in body,
                    "$section must exclude $domain: nothing leaves the phone (owner, DESIGN §5).",
                )
            }
            assertTrue("<include" !in body, "$section includes something: nothing leaves the phone (owner, DESIGN §5).")
        }
    }

    @Test
    fun `the release key and Play access stay out of the repo (DESIGN 6)`() {
        val skip = setOf("build", ".gradle", ".git", ".kotlin", ".idea", "node_modules")
        val files = repo.canonicalFile.walkTopDown().onEnter { it.name !in skip }.filter { it.isFile }.toList()
        val keyFiles = files.filter { it.extension.lowercase() in setOf("jks", "keystore", "p12", "pfx", "pem") }.map(::rel)
        assertTrue(
            keyFiles == listOf("keystore/debug.keystore"),
            "key files in the repo: $keyFiles. Only the debug keystore is committed; the Play upload key lives in GitHub " +
                "secrets only (make-release-key workflow, DESIGN §6).",
        )
        // Built from parts so this test doesn't match itself.
        val secrets = listOf("-----BEGIN " + "PRIVATE KEY-----", "\"type\": \"" + "service_account\"")
        val leaks = files.filter { it.length() < 2_000_000 && it.extension !in setOf("png", "jpg", "pcm", "jar", "keystore") }
            .filter { f -> f.readText().let { t -> secrets.any { it in t } } }.map(::rel)
        assertTrue(leaks.isEmpty(), "a private key or service-account key in $leaks. Delete it, rotate it, and keep it in GitHub secrets only.")
        val gradle = File("build.gradle.kts").readText()
        assertTrue(
            Regex("""storePassword\s*=\s*"""").findAll(gradle).count() == 1 && "environmentVariable(\"RELEASE_KEYSTORE_PASSWORD\")" in gradle,
            "the release signing config must read its keystore and password from the environment (GitHub secrets in CI), " +
                "never from a value in the repo. Only the debug key's password is written down.",
        )
    }

    @Test
    fun `the debug APK installs next to the Play build (DESIGN 6)`() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(
            Regex("""debug\s*\{[^}]*applicationIdSuffix\s*=\s*"\.debug"""").containsMatchIn(gradle),
            "the debug build must keep its own application ID, or installing it would replace (or be refused by) the Play build.",
        )
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val authorities = Regex("""android:authorities="([^"]*)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue(
            authorities.all { it.startsWith("\${applicationId}") },
            "provider authorities $authorities must start with \${applicationId}: two installs can't share one.",
        )
        val name = Regex("""name="app_name">([^<]*)<""")
        val main = name.find(File("src/main/res/values/strings.xml").readText())?.groupValues?.get(1)
        val debug = name.find(File("src/debug/res/values/strings.xml").readText())?.groupValues?.get(1)
        assertTrue(debug != null && debug != main, "the debug app needs its own name on the home screen (it is \"$debug\", Play's is \"$main\").")
    }

    @Test
    fun `no comment justifies a workaround`() {
        val phrases = listOf(
            "workaround", "work around", "hack", "for now", "quick fix", "temporary fix", "band-aid", "bandaid",
            "TODO", "FIXME", "XXX",
        )
        val hits = mains.flatMap(::kt).flatMap { f ->
            f.readLines().withIndex().flatMap { (i, l) ->
                val c = commentText(l) ?: return@flatMap emptyList()
                phrases.filter { c.contains(it, ignoreCase = it.lowercase() == it) }.map { "${rel(f)}:${i + 1}: \"$it\"" }
            }
        }
        assertTrue(
            hits.isEmpty(),
            "$hits. A comment that explains a shortcut gets copied as the pattern. Fix the root cause; if that is out " +
                "of scope, leave the code as it was and report it in the PR (CLAUDE.md \"Code quality\").",
        )
    }

    /**
     * Escape hatches may only go down. `!!`, `@Suppress`, `runCatching` and catch-alls (`catch (e: Exception)`,
     * `Throwable`, `RuntimeException`, `Error`) hide a problem instead of fixing it, so each file's count is pinned
     * in `escape-hatches.txt`. Catching the one exception an API documents (`ActivityNotFoundException`,
     * `IOException`) is handling it, so that doesn't count. Some are the right design (the ring path must survive
     * any failure: see `isolated`), which is why this is a baseline and not a ban.
     */
    @Test
    fun `escape hatches never increase`() {
        val kinds = mapOf(
            "!!" to Regex("!!"),
            "@Suppress" to Regex("@Suppress"),
            // runCatching is a catch-all too (it catches Throwable): turning one into the other is neither better nor worse.
            "catch-all" to Regex("""\brunCatching\b|catch\s*\(\s*\w+\s*:\s*(Throwable|Exception|RuntimeException|Error)\s*\)"""),
        )
        val baselineFile = File("src/test/resources/escape-hatches.txt")
        val baseline = baselineFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val (path, kind, n) = line.split("\t")
            (path to kind) to n.toInt()
        }
        val actual = mains.flatMap(::kt).flatMap { f ->
            val lines = code(f).map { it.value }
            kinds.map { (k, re) -> (rel(f) to k) to lines.sumOf { re.findAll(it).count() } }
        }.filter { it.second > 0 }.toMap()

        val over = actual.filter { (key, n) -> n > (baseline[key] ?: 0) }
            .map { (key, n) -> "${key.first}: ${key.second} x$n (allowed ${baseline[key] ?: 0})" }
        val under = baseline.filter { (key, n) -> (actual[key] ?: 0) < n }
            .map { (key, n) -> "${key.first}\t${key.second}\t${actual[key] ?: 0}  (was $n)" }
        assertTrue(
            over.isEmpty(),
            "new escape hatches: $over. Fix the cause instead: make the type non-null, handle the error where it can be " +
                "handled, or use the current API. If one truly is the right design, raise its line in " +
                "${baselineFile.path} and give the reason under \"Escape hatches\" in the PR.",
        )
        assertTrue(
            under.isEmpty(),
            "fewer escape hatches than the baseline, good. Lower these lines in ${baselineFile.path} so they can't " +
                "creep back (drop a line that reaches 0): $under",
        )
    }
}
