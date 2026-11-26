package com.nx.android.data

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * The game profiles package of this build against the player's own layer.
 *
 * The app does not carry the profiles (they are half a gigabyte of shader tables): the player imports a package into
 * their own folder (nx2a/profiles) and keeps it. The app carries the package's manifest (assets/profiles.json): the
 * title IDs that package holds.
 *
 * A package that was cut before a game was ported does not hold that game, so the porter finds no profile for it and
 * the port has to be an experimental one. That is the player's package being older, not the game being unsupported.
 */
class ProfilesPack(private val ids: Set<String>, private val layer: File) {

    /** The package of this build has the game */
    fun has(titleId: String): Boolean = titleId in ids

    /** The player's own layer holds the game's profile */
    fun imported(titleId: String): Boolean = File(layer, titleId).isDirectory

    /** The package of this build has the game and the player's layer does not: the player's package is older */
    fun stale(titleId: String): Boolean = titleId.isNotEmpty() && has(titleId) && !imported(titleId)

    companion object {
        const val BUNDLED_MANIFEST = "profiles/bundled.json"
        const val BUNDLED_STAMP_FILE = ".nx2a-bundled-sha256"

        /** The package of this build, read from the app's own assets */
        fun of(app: Context, layer: File): ProfilesPack = ProfilesPack(readIds(app), layer)

        /**
         * Ensures built-in APK profiles (`assets/profiles/<TITLE_ID>/`) are extracted into the player's
         * `nx2a/profiles/` layer so supported games port and run out of the box without downloading the full zip.
         */
        fun ensureBundled(app: Context, layer: File, force: Boolean = false): Boolean {
            val manifestText = runCatching {
                app.assets.open(BUNDLED_MANIFEST).use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull() ?: return false
            val json = runCatching { JSONObject(manifestText) }.getOrNull() ?: return false
            val array = json.optJSONArray("ids") ?: return false
            val sha256 = json.optString("sha256", "")
            var changed = false
            for (i in 0 until array.length()) {
                val tid = array.optString(i).trim()
                if (tid.isEmpty()) continue
                val assetFiles = runCatching { app.assets.list("profiles/$tid") }.getOrNull()?.filter { it.isNotEmpty() }
                    ?: continue
                changed = installProfile(
                    layer = layer,
                    titleId = tid,
                    sha256 = sha256,
                    fileNames = assetFiles,
                    force = force,
                    openFile = { name -> app.assets.open("profiles/$tid/$name").use { it.readBytes() } },
                ) || changed
            }
            return changed
        }

        /** Pure-filesystem entry point for JVM tests and directory-based bundles. */
        internal fun installBundledFromDirectory(sourceProfilesDir: File, layer: File, force: Boolean = false): Boolean {
            val manifest = File(sourceProfilesDir, "bundled.json")
            if (!manifest.isFile) return false
            val json = runCatching { JSONObject(manifest.readText(Charsets.UTF_8)) }.getOrNull() ?: return false
            val array = json.optJSONArray("ids") ?: return false
            val sha256 = json.optString("sha256", "")
            var changed = false
            for (i in 0 until array.length()) {
                val tid = array.optString(i).trim()
                if (tid.isEmpty()) continue
                val srcDir = File(sourceProfilesDir, tid)
                if (!srcDir.isDirectory) continue
                val files = srcDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.map { it.name }
                    ?: continue
                changed = installProfile(
                    layer = layer,
                    titleId = tid,
                    sha256 = sha256,
                    fileNames = files,
                    force = force,
                    openFile = { name -> File(srcDir, name).readBytes() },
                ) || changed
            }
            return changed
        }

        private fun installProfile(
            layer: File,
            titleId: String,
            sha256: String,
            fileNames: List<String>,
            force: Boolean,
            openFile: (String) -> ByteArray,
        ): Boolean {
            if (fileNames.isEmpty()) return false
            val targetDir = File(layer, titleId)
            val stamp = File(targetDir, BUNDLED_STAMP_FILE)
            val currentStamp = runCatching { if (stamp.isFile) stamp.readText(Charsets.UTF_8).trim() else "" }.getOrDefault("")
            if (!force && File(targetDir, "game.tsv").isFile && sha256.isNotEmpty() && currentStamp == sha256) {
                return false
            }
            targetDir.mkdirs()
            for (name in fileNames) {
                val bytes = openFile(name)
                val dst = File(targetDir, name)
                val existing = if (dst.isFile && dst.length() == bytes.size.toLong()) runCatching { dst.readBytes() }.getOrNull() else null
                if (existing == null || !existing.contentEquals(bytes)) {
                    val tmp = File(targetDir, "$name.tmp")
                    tmp.writeBytes(bytes)
                    if (dst.exists()) dst.delete()
                    if (!tmp.renameTo(dst)) tmp.copyTo(dst, overwrite = true).also { tmp.delete() }
                }
            }
            if (sha256.isNotEmpty()) stamp.writeText(sha256 + "\n", Charsets.UTF_8)
            return true
        }

        /** The title IDs the package or built-in bundle holds */
        private fun readIds(app: Context): Set<String> {
            val out = LinkedHashSet<String>()
            for (asset in listOf("profiles.json", BUNDLED_MANIFEST)) {
                runCatching {
                    val text = app.assets.open(asset).use { it.readBytes().toString(Charsets.UTF_8) }
                    val array = JSONObject(text).optJSONArray("ids") ?: return@runCatching
                    for (i in 0 until array.length()) {
                        val id = array.optString(i).trim()
                        if (id.isNotEmpty()) out.add(id)
                    }
                }
            }
            return out
        }
    }
}
