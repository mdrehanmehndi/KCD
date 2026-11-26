package com.nx.android.data

import android.os.Environment
import com.nx.android.BuildConfig
import com.nx.android.core.storage.AppPaths
import com.nx.android.domain.PlayerApiRevision
import com.nx.android.domain.Ported
import com.nx.android.domain.RuntimeBuildMarker
import java.io.File

/**
 * Developer fast-iteration bridge for unrooted devices (e.g. Termux -> /sdcard/Download/nx2android/dev/):
 *
 * Because unrooted Android isolates app-private `files/` from shell tools like Termux, while this app
 * already holds all-files access (`MANAGE_EXTERNAL_STORAGE`), a developer can stage overrides under
 * `/sdcard/Download/nx2android/dev/<TITLE_ID>/` and let the app pull them into its private pack/runtime
 * folders before launching, then mirror `log.txt`, `log.prev.txt`, and `rebuild.log` back out to
 * `<dev>/<TITLE_ID>/logs/` for inspection.
 *
 * Pure file-operation core takes explicit roots so unit tests run on the JVM without Android.
 */
object DevSync {

    const val ACTION_LAUNCH = "com.nx.android.action.DEV_LAUNCH"
    const val EXTRA_TITLE_ID = "title_id"
    const val EXTRA_PACK = "pack"
    const val EXTRA_SYNC = "sync"
    const val EXTRA_REBUILD = "rebuild"
    const val EXTRA_LAUNCH = "launch"

    const val DEV_SUBDIR = "nx2android/dev"

    private const val LOGS_DIR = "logs"
    private val TITLE_ID_RE = Regex("^[0-9A-Fa-f]{16}$")

    /** Device/UI-owned env.txt keys kept from the existing pack unless explicitly overridden in dev/env.txt. */
    private val MANAGED_ENV_KEYS = setOf("DM_ROMFS", "DM_AOC_DIR", "DM_SYSDATA", "DM_LANG", "DM_VK_DRIVER")

    /** Runtime binary names accepted from dev/<TITLE_ID>/ (or runtime/, runtimes/, host/). */
    val HOST_BINARIES: Set<String> = setOf("libdmplay.so") + RuntimeStorage.HOOKS

    data class SyncResult(
        val titleId: String,
        val filesSynced: Int = 0,
        val runtimeSynced: Int = 0,
        val logsMirrored: Int = 0,
    )

    fun defaultDevRoot(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DEV_SUBDIR)

    /** Finds `/sdcard/Download/nx2android/dev/<TITLE_ID>` matching [titleId] case-insensitively. */
    fun findGameDevDir(devRoot: File, titleId: String): File? {
        if (!devRoot.isDirectory || !TITLE_ID_RE.matches(titleId)) return null
        val exactUpper = File(devRoot, titleId.uppercase())
        if (exactUpper.isDirectory) return exactUpper
        val exactLower = File(devRoot, titleId.lowercase())
        if (exactLower.isDirectory) return exactLower
        return devRoot.listFiles()?.firstOrNull {
            it.isDirectory && it.name.equals(titleId, ignoreCase = true)
        }
    }

    /**
     * Lists staged 16-hex Title IDs under [devRoot] sorted by newest modification first, ignoring
     * directories that only hold mirrored `logs/`.
     */
    fun stagedTitleIds(devRoot: File): List<String> {
        if (!devRoot.isDirectory) return emptyList()
        return devRoot.listFiles()
            ?.filter { it.isDirectory && TITLE_ID_RE.matches(it.name) && hasStagedInputs(it) }
            ?.sortedByDescending { latestInputModified(it) }
            ?.map { it.name.uppercase() }
            ?: emptyList()
    }

    /**
     * Merges a developer-supplied `env.txt` with [existingEnv]: every line/comment from [devEnv]
     * stays authoritative, while missing device/UI-bound keys (`DM_ROMFS`, `DM_AOC_DIR`, `DM_SYSDATA`,
     * `DM_LANG`, `DM_VK_DRIVER`) from [existingEnv] are preserved at the end so RomFS/DLC paths survive.
     */
    fun mergeEnvText(existingEnv: String, devEnv: String): String {
        val devKeys = HashSet<String>()
        val out = devEnv.split('\n').map { it.trimEnd('\r') }.toMutableList()
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        for (line in out) {
            val k = envKeyOf(line)
            if (k != null) devKeys.add(k)
        }
        val preserved = HashSet<String>()
        for (raw in existingEnv.split('\n')) {
            val line = raw.trimEnd('\r')
            val k = envKeyOf(line) ?: continue
            if (k in MANAGED_ENV_KEYS && k !in devKeys && preserved.add(k)) {
                out.add(line)
            }
        }
        return if (out.isEmpty()) "" else out.joinToString("\n") + "\n"
    }

    /** Syncs staged overrides and runtime binaries for [ported], then mirrors logs out to shared storage. */
    fun syncGame(
        ported: Ported,
        paths: AppPaths,
        devRoot: File = defaultDevRoot(),
        noLsfg: Boolean = !BuildConfig.NX_LSFG,
        playerApiRevision: Int = PlayerApiRevision.CURRENT,
    ): SyncResult = syncGame(
        ported = ported,
        runtimesDir = paths.runtimes,
        nxHomeDir = paths.nxHome,
        devRoot = devRoot,
        noLsfg = noLsfg,
        playerApiRevision = playerApiRevision,
    )

    fun syncGame(
        ported: Ported,
        runtimesDir: File,
        nxHomeDir: File,
        devRoot: File,
        noLsfg: Boolean = !BuildConfig.NX_LSFG,
        playerApiRevision: Int = PlayerApiRevision.CURRENT,
    ): SyncResult {
        var filesSynced = 0
        var runtimeSynced = 0
        val devDir = findGameDevDir(devRoot, ported.titleId)

        if (devDir != null && devDir.isDirectory) {
            // 1. env.txt & settings.txt overrides
            val devEnvFile = File(devDir, "env.txt")
            if (devEnvFile.isFile) {
                val packEnv = File(ported.pack, "env.txt")
                val existing = runCatching { packEnv.readText(Charsets.UTF_8) }.getOrDefault("")
                val merged = mergeEnvText(existing, devEnvFile.readText(Charsets.UTF_8))
                if (merged != existing && writeTextAtomic(packEnv, merged)) {
                    filesSynced++
                }
            }
            val devSettings = File(devDir, "settings.txt")
            if (devSettings.isFile) {
                if (syncFileIfChanged(devSettings, File(ported.pack, "settings.txt"))) filesSynced++
            }

            // 2. RomFS overrides (supports app-mode romfs_at workspaces as well as packed RomFS/)
            val romfsTarget = if (ported.romfsAt.isNotEmpty() && File(ported.romfsAt).isDirectory) {
                File(ported.romfsAt)
            } else {
                File(ported.pack, "RomFS")
            }
            val devRomfs = File(devDir, "RomFS")
            if (devRomfs.isDirectory) {
                filesSynced += syncTree(devRomfs, romfsTarget)
            }
            val flatUserCfg = File(devDir, "user.cfg")
            if (flatUserCfg.isFile && !File(devRomfs, "user.cfg").isFile) {
                if (syncFileIfChanged(flatUserCfg, File(romfsTarget, "user.cfg"))) filesSynced++
            }

            // 3. Compiled shader bundle & shader/profile tables
            val devShadersDmsv = File(devDir, "shaders.dmsv")
            if (devShadersDmsv.isFile) {
                if (syncFileIfChanged(devShadersDmsv, File(ported.pack, "shaders.dmsv"))) filesSynced++
            }
            val devShadersDir = File(devDir, "shaders")
            if (devShadersDir.isDirectory) {
                filesSynced += syncTree(devShadersDir, File(ported.pack, "shaders"))
            }
            val profileTarget = File(nxHomeDir, "profiles/${ported.titleId.uppercase()}")
            val devProfileDir = File(devDir, "profile")
            if (devProfileDir.isDirectory) {
                filesSynced += syncTree(devProfileDir, profileTarget)
            }
            devDir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".tsv") || f.name == "patches.py")) {
                    if (!File(devProfileDir, f.name).isFile) {
                        if (syncFileIfChanged(f, File(profileTarget, f.name))) filesSynced++
                    }
                }
            }

            // 4. Prebuilt host & hook .so binaries into files/runtimes/<titleId>/ (skips on-device NDK rebuild)
            val stagedBins = collectRuntimeBinaries(devDir)
            if (stagedBins.isNotEmpty()) {
                val targetDir = File(runtimesDir, ported.titleId.lowercase())
                for ((name, src) in stagedBins) {
                    if (syncFileIfChanged(src, File(targetDir, name))) {
                        runtimeSynced++
                    }
                    // Keep legacy hostDeclared packs aligned too if a host/ folder was declared
                    if (ported.hostDeclared) {
                        val legacyTarget = if (name == "libdmplay.so") ported.host
                        else File(ported.pack, "drivers/$name")
                        if (syncFileIfChanged(src, legacyTarget)) {
                            runtimeSynced++
                        }
                    }
                }
                if (runtimeComplete(targetDir) && (runtimeSynced > 0 || !markerValid(targetDir, ported, noLsfg, playerApiRevision))) {
                    writeMarker(
                        targetDir,
                        RuntimeBuildMarker(
                            playerApiRevision = playerApiRevision,
                            profileH = ported.profileH,
                            noLsfg = noLsfg,
                        ),
                    )
                }
            }
        }

        val logsMirrored = mirrorLogs(ported.pack, ported.titleId, devRoot)
        return SyncResult(
            titleId = ported.titleId.uppercase(),
            filesSynced = filesSynced,
            runtimeSynced = runtimeSynced,
            logsMirrored = logsMirrored,
        )
    }

    /** Copies `log.txt`, `log.prev.txt`, and `rebuild.log` out to `<dev>/<TITLE_ID>/logs/` if present and changed. */
    fun mirrorLogs(pack: File, titleId: String, devRoot: File = defaultDevRoot()): Int {
        if (!pack.isDirectory || !TITLE_ID_RE.matches(titleId)) return 0
        val logs = GameLog.logs(pack)
        if (logs.isEmpty()) return 0
        val baseDir = findGameDevDir(devRoot, titleId) ?: File(devRoot, titleId.uppercase())
        val dstDir = File(baseDir, LOGS_DIR)
        var mirrored = 0
        for ((name, src) in logs) {
            if (syncFileIfChanged(src, File(dstDir, name))) mirrored++
        }
        return mirrored
    }

    /** Mirrors logs across all supplied [packs] that currently contain runtime or rebuild logs. */
    fun mirrorAllLogs(packs: List<Ported>, devRoot: File = defaultDevRoot()): Int {
        var total = 0
        for (p in packs) {
            total += mirrorLogs(p.pack, p.titleId, devRoot)
        }
        return total
    }

    private fun envKeyOf(line: String): String? {
        val trimmed = line.trimStart()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        return line.substring(0, eq).trim().takeIf { it.isNotEmpty() }
    }

    private fun hasStagedInputs(dir: File): Boolean {
        val children = dir.listFiles() ?: return false
        return children.any { child ->
            if (child.name == LOGS_DIR) false
            else if (child.isFile) true
            else child.isDirectory && hasAnyFile(child)
        }
    }

    private fun hasAnyFile(dir: File): Boolean {
        val children = dir.listFiles() ?: return false
        return children.any { it.isFile || (it.isDirectory && hasAnyFile(it)) }
    }

    private fun latestInputModified(dir: File): Long {
        var max = dir.lastModified()
        val stack = ArrayDeque<File>()
        dir.listFiles()?.filter { it.name != LOGS_DIR }?.forEach { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val f = stack.removeFirst()
            if (f.lastModified() > max) max = f.lastModified()
            if (f.isDirectory) f.listFiles()?.forEach { stack.addLast(it) }
        }
        return max
    }

    private fun collectRuntimeBinaries(devDir: File): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        // Lowest -> highest precedence so `runtime/` wins if duplicate files exist.
        val searchDirs = listOf(
            devDir,
            File(devDir, "host"),
            File(devDir, "runtimes"),
            File(devDir, "runtime"),
        )
        for (d in searchDirs) {
            if (!d.isDirectory) continue
            for (name in HOST_BINARIES) {
                val f = File(d, name)
                if (f.isFile) out[name] = f
            }
        }
        return out
    }

    private fun runtimeComplete(dir: File): Boolean =
        File(dir, "libdmplay.so").isFile && RuntimeStorage.HOOKS.all { File(dir, it).isFile }

    private fun markerValid(dir: File, ported: Ported, noLsfg: Boolean, expectedApiRev: Int): Boolean {
        val f = File(dir, RuntimeStorage.MARKER_FILE)
        if (!f.isFile) return false
        val marker = LibraryFileScanner.readMarker(dir) ?: return false
        if (marker.playerApiRevision != expectedApiRev) return false
        if (marker.noLsfg != null && marker.noLsfg != noLsfg) return false
        if (ported.profileH.isNotEmpty() && marker.profileH.isNotEmpty() && ported.profileH != marker.profileH) return false
        return true
    }

    private fun writeMarker(dir: File, marker: RuntimeBuildMarker) {
        val json = buildString {
            append('{')
            append("\"player_api_revision\":").append(marker.playerApiRevision)
            append(",\"profile_h\":\"").append(marker.profileH).append('"')
            marker.noLsfg?.let { append(",\"no_lsfg\":").append(it) }
            append('}')
        }
        writeTextAtomic(File(dir, RuntimeStorage.MARKER_FILE), json)
    }

    private fun syncTree(srcDir: File, dstDir: File): Int {
        if (!srcDir.isDirectory) return 0
        var count = 0
        val children = srcDir.listFiles() ?: return 0
        for (child in children) {
            val target = File(dstDir, child.name)
            if (child.isDirectory) {
                count += syncTree(child, target)
            } else if (child.isFile) {
                if (syncFileIfChanged(child, target)) count++
            }
        }
        return count
    }

    internal fun syncFileIfChanged(src: File, dst: File): Boolean {
        if (!src.isFile) return false
        val srcLen = src.length()
        val srcMtime = src.lastModified()
        if (dst.isFile && dst.length() == srcLen && dst.lastModified() == srcMtime) {
            return false
        }
        dst.parentFile?.mkdirs()
        val part = File(dst.parentFile, "${dst.name}.part")
        src.inputStream().use { input ->
            part.outputStream().use { output -> input.copyTo(output) }
        }
        if (!part.renameTo(dst)) {
            part.copyTo(dst, overwrite = true)
            part.delete()
        }
        if (srcMtime > 0L) runCatching { dst.setLastModified(srcMtime) }
        return true
    }

    private fun writeTextAtomic(dst: File, content: String): Boolean {
        dst.parentFile?.mkdirs()
        val part = File(dst.parentFile, "${dst.name}.part")
        part.writeText(content, Charsets.UTF_8)
        if (!part.renameTo(dst)) {
            part.copyTo(dst, overwrite = true)
            part.delete()
        }
        return true
    }
}
