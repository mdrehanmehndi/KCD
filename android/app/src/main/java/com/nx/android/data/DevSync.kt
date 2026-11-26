package com.nx.android.data

import android.content.Context
import android.os.Environment
import com.nx.android.BuildConfig
import com.nx.android.core.storage.AppPaths
import com.nx.android.data.library.LibraryFileScanner
import com.nx.android.domain.PlayerApiRevision
import com.nx.android.domain.Ported
import com.nx.android.domain.RuntimeBuildMarker
import java.io.File

/**
 * Developer fast-iteration bridge for unrooted devices (`/sdcard/Download/nx2a_dev/<lowercase_title_id>/`).
 *
 * When a game's dev folder exists in shared Downloads storage and All Files Access is granted:
 *  - Pre-launch sync pulls updated `libdmplay.so` (with +rx permissions and `build.json` refresh so no rebuild
 *    is demanded), `shaders.dmsv`, `env.txt`, `nx2a-data.json`, and `user.cfg` (or `RomFS/`).
 *  - Live / pause / exit / crash export mirrors `log.txt`, `log.prev.txt`, `device.txt`, `game.txt`,
 *    `rebuild.log`, and any `captured_shaders/*.bin` out to `<dev_dir>/out/` for Termux inspection.
 */
object DevSync {

    const val ACTION_LAUNCH = "com.nx.android.ACTION_DEV_LAUNCH"
    const val LEGACY_ACTION_LAUNCH = "com.nx.android.action.DEV_LAUNCH"
    const val EXTRA_TITLE_ID = "title_id"
    const val EXTRA_PACK = "pack"
    const val EXTRA_SYNC = "sync"
    const val EXTRA_REBUILD = "rebuild"
    const val EXTRA_LAUNCH = "launch"

    const val DEV_SUBDIR = "nx2a_dev"

    private const val OUT_DIR = "out"
    private val TITLE_ID_RE = Regex("^[0-9A-Fa-f]{16}$")

    /** Device/UI-owned env.txt keys preserved from the existing pack unless explicitly set in dev/env.txt. */
    private val MANAGED_ENV_KEYS = setOf("DM_ROMFS", "DM_AOC_DIR", "DM_SYSDATA", "DM_LANG", "DM_VK_DRIVER")

    /** Runtime binary names accepted from `nx2a_dev/<lowercase_title_id>/`. */
    val HOST_BINARIES: Set<String> = setOf("libdmplay.so") + RuntimeStorage.HOOKS

    data class SyncResult(
        val titleId: String,
        val pulledFiles: List<String> = emptyList(),
        val exportedFiles: List<String> = emptyList(),
    ) {
        val filesSynced: Int get() = pulledFiles.size
        val logsMirrored: Int get() = exportedFiles.size
    }

    fun defaultDevRoot(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DEV_SUBDIR)

    /** Returns `/sdcard/Download/nx2a_dev/<lowercase_title_id>` if it exists (with case-insensitive fallback). */
    fun findGameDevDir(devRoot: File, titleId: String): File? {
        if (!devRoot.isDirectory || !TITLE_ID_RE.matches(titleId)) return null
        val lower = File(devRoot, titleId.lowercase())
        if (lower.isDirectory) return lower
        val upper = File(devRoot, titleId.uppercase())
        if (upper.isDirectory) return upper
        return devRoot.listFiles()?.firstOrNull {
            it.isDirectory && it.name.equals(titleId, ignoreCase = true)
        }
    }

    fun hasGameDevDir(titleId: String, devRoot: File = defaultDevRoot()): Boolean =
        findGameDevDir(devRoot, titleId) != null

    /** Lists 16-hex Title IDs under [devRoot] sorted by newest modification first (preferring dirs with inputs). */
    fun stagedTitleIds(devRoot: File): List<String> {
        if (!devRoot.isDirectory) return emptyList()
        return devRoot.listFiles()
            ?.filter { it.isDirectory && TITLE_ID_RE.matches(it.name) && hasStagedInputs(it) }
            ?.sortedByDescending { latestInputModified(it) }
            ?.map { it.name.lowercase() }
            ?: emptyList()
    }

    /**
     * Merges developer `env.txt` with [existingEnv]: all lines/comments in [devEnv] take priority,
     * while missing device/UI-bound keys (`DM_ROMFS`, `DM_AOC_DIR`, `DM_SYSDATA`, `DM_LANG`, `DM_VK_DRIVER`)
     * from [existingEnv] stay intact.
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

    fun syncGame(
        ported: Ported,
        paths: AppPaths,
        context: Context? = null,
        devRoot: File = defaultDevRoot(),
        noLsfg: Boolean = !BuildConfig.NX_LSFG,
        playerApiRevision: Int = PlayerApiRevision.CURRENT,
    ): SyncResult = syncGame(
        ported = ported,
        runtimesDir = paths.runtimes,
        nxHomeDir = paths.nxHome,
        devRoot = devRoot,
        deviceText = context?.let { runCatching { LogBundle.deviceText(it) }.getOrNull() },
        noLsfg = noLsfg,
        playerApiRevision = playerApiRevision,
    )

    fun syncGame(
        ported: Ported,
        runtimesDir: File,
        nxHomeDir: File,
        devRoot: File,
        deviceText: String? = null,
        noLsfg: Boolean = !BuildConfig.NX_LSFG,
        playerApiRevision: Int = PlayerApiRevision.CURRENT,
    ): SyncResult {
        val devDir = findGameDevDir(devRoot, ported.titleId) ?: return SyncResult(ported.titleId.lowercase())
        val pulled = ArrayList<String>()

        // 1. Sync nx2a-data.json first so subsequent manifest reads (profile_h, romfs_at) see the latest fields.
        val devManifest = File(devDir, "nx2a-data.json")
        if (devManifest.isFile && syncFileIfChanged(devManifest, File(ported.pack, "nx2a-data.json"))) {
            pulled.add("nx2a-data.json")
        }
        val effectivePorted = LibraryFileScanner.portFromPack(ported.pack, runtimesDir) ?: ported

        // 2. Sync shaders.dmsv & env.txt into the pack directory.
        val devShadersDmsv = File(devDir, "shaders.dmsv")
        if (devShadersDmsv.isFile && syncFileIfChanged(devShadersDmsv, File(effectivePorted.pack, "shaders.dmsv"))) {
            pulled.add("shaders.dmsv")
        }
        val devEnvFile = File(devDir, "env.txt")
        if (devEnvFile.isFile) {
            val packEnv = File(effectivePorted.pack, "env.txt")
            val existing = runCatching { packEnv.readText(Charsets.UTF_8) }.getOrDefault("")
            val merged = mergeEnvText(existing, devEnvFile.readText(Charsets.UTF_8))
            if (merged != existing && writeTextAtomic(packEnv, merged)) {
                pulled.add("env.txt")
            }
        }
        val devSettings = File(devDir, "settings.txt")
        if (devSettings.isFile && syncFileIfChanged(devSettings, File(effectivePorted.pack, "settings.txt"))) {
            pulled.add("settings.txt")
        }

        // 3. Sync user.cfg (and optional RomFS/ tree) into the writable RomFS folder.
        val romfsTarget = if (effectivePorted.romfsAt.isNotEmpty()) {
            File(effectivePorted.romfsAt)
        } else {
            File(effectivePorted.pack, "RomFS")
        }
        val devRomfs = File(devDir, "RomFS")
        if (devRomfs.isDirectory && ensureWritableDir(romfsTarget)) {
            syncTree(devRomfs, romfsTarget, prefix = "RomFS", pulled = pulled)
        }
        val devUserCfg = File(devDir, "user.cfg")
        if (devUserCfg.isFile && !File(devRomfs, "user.cfg").isFile && ensureWritableDir(romfsTarget)) {
            if (syncFileIfChanged(devUserCfg, File(romfsTarget, "user.cfg"))) {
                pulled.add("user.cfg")
            }
        }

        // 4. Sync optional profile/*.tsv or shaders/ overrides if staged.
        val devShadersDir = File(devDir, "shaders")
        if (devShadersDir.isDirectory) {
            syncTree(devShadersDir, File(effectivePorted.pack, "shaders"), prefix = "shaders", pulled = pulled)
        }
        val profileTarget = File(nxHomeDir, "profiles/${effectivePorted.titleId.uppercase()}")
        val devProfileDir = File(devDir, "profile")
        if (devProfileDir.isDirectory) {
            syncTree(devProfileDir, profileTarget, prefix = "profile", pulled = pulled)
        }
        devDir.listFiles()?.forEach { f ->
            if (f.isFile && (f.name.endsWith(".tsv") || f.name == "patches.py") && !File(devProfileDir, f.name).isFile) {
                if (syncFileIfChanged(f, File(profileTarget, f.name))) {
                    pulled.add(f.name)
                }
            }
        }

        // 5. Sync libdmplay.so (and optional hook .so files) into files/runtimes/<lowercase_title_id>/ and refresh build.json.
        val stagedBins = collectRuntimeBinaries(devDir)
        if (stagedBins.isNotEmpty()) {
            val targetDir = File(runtimesDir, effectivePorted.titleId.lowercase())
            targetDir.mkdirs()
            // Ensure companion hooks exist in runtimes/<tid>/ if the pack carries them in drivers/
            val packDrivers = File(effectivePorted.pack, "drivers")
            if (packDrivers.isDirectory) {
                for (hook in RuntimeStorage.HOOKS) {
                    val dstHook = File(targetDir, hook)
                    val packHook = File(packDrivers, hook)
                    if (!dstHook.isFile && packHook.isFile && syncFileIfChanged(packHook, dstHook, executable = true)) {
                        pulled.add("drivers/$hook->runtime")
                    }
                }
            }
            var runtimeChanged = false
            for ((name, src) in stagedBins) {
                val dst = File(targetDir, name)
                if (syncFileIfChanged(src, dst, executable = true)) {
                    pulled.add(name)
                    runtimeChanged = true
                } else {
                    ensurePermissions(dst, executable = true)
                }
                if (effectivePorted.hostDeclared) {
                    val legacyTarget = if (name == "libdmplay.so") effectivePorted.host
                    else File(effectivePorted.pack, "drivers/$name")
                    syncFileIfChanged(src, legacyTarget, executable = true)
                }
            }
            if (runtimeComplete(targetDir) && (runtimeChanged || !markerValid(targetDir, effectivePorted, noLsfg, playerApiRevision))) {
                writeMarker(
                    targetDir,
                    RuntimeBuildMarker(
                        playerApiRevision = playerApiRevision,
                        profileH = effectivePorted.profileH,
                        noLsfg = noLsfg,
                    ),
                )
                pulled.add(RuntimeStorage.MARKER_FILE)
            }
        }

        val exported = exportOutputs(
            pack = effectivePorted.pack,
            titleId = effectivePorted.titleId,
            ported = effectivePorted,
            deviceText = deviceText,
            devRoot = devRoot,
        )
        return SyncResult(
            titleId = effectivePorted.titleId.lowercase(),
            pulledFiles = pulled,
            exportedFiles = exported,
        )
    }

    /**
     * Mirrors `log.txt`, `log.prev.txt`, `rebuild.log`, `device.txt`, `game.txt`, and `captured_shaders/*.bin`
     * into `/sdcard/Download/nx2a_dev/<lowercase_title_id>/out/`, ONLY if the dev folder already exists.
     */
    fun exportOutputs(
        pack: File,
        titleId: String,
        ported: Ported? = null,
        deviceText: String? = null,
        devRoot: File = defaultDevRoot(),
    ): List<String> {
        if (!pack.isDirectory || !TITLE_ID_RE.matches(titleId)) return emptyList()
        val devDir = findGameDevDir(devRoot, titleId) ?: return emptyList()
        val outDir = File(devDir, OUT_DIR)
        val exported = ArrayList<String>()

        for ((name, src) in GameLog.logs(pack)) {
            if (syncFileIfChanged(src, File(outDir, name))) {
                exported.add(name)
            }
        }
        val effectivePorted = ported ?: LibraryFileScanner.portFromPack(pack)
        if (effectivePorted != null) {
            val facts = GameLog.facts(
                effectivePorted.name,
                effectivePorted.titleId,
                effectivePorted.display,
                effectivePorted.status,
                effectivePorted.playerApiRevision,
            )
            val gameFile = File(outDir, "game.txt")
            val prevGame = runCatching { gameFile.readText(Charsets.UTF_8) }.getOrNull()
            if (prevGame != facts && writeTextAtomic(gameFile, facts)) {
                exported.add("game.txt")
            }
        }
        if (!deviceText.isNullOrBlank()) {
            val deviceFile = File(outDir, "device.txt")
            val prevDevice = runCatching { deviceFile.readText(Charsets.UTF_8) }.getOrNull()
            if (prevDevice != deviceText && writeTextAtomic(deviceFile, deviceText)) {
                exported.add("device.txt")
            }
        }
        val capDir = File(pack, "captured_shaders")
        if (capDir.isDirectory) {
            val capOut = File(outDir, "captured_shaders")
            capDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".bin") && syncFileIfChanged(f, File(capOut, f.name))) {
                    exported.add("captured_shaders/${f.name}")
                }
            }
        }
        return exported
    }

    /** Convenience overload for live/pause/resume hooks with a [Context] to include `device.txt`. */
    fun exportOutputs(
        context: Context,
        pack: File,
        titleId: String,
        ported: Ported? = null,
        devRoot: File = defaultDevRoot(),
    ): List<String> = exportOutputs(
        pack = pack,
        titleId = titleId,
        ported = ported,
        deviceText = runCatching { LogBundle.deviceText(context) }.getOrNull(),
        devRoot = devRoot,
    )

    fun exportAllOutputs(
        context: Context,
        packs: List<Ported>,
        devRoot: File = defaultDevRoot(),
    ): Int {
        if (!devRoot.isDirectory) return 0
        val deviceText = runCatching { LogBundle.deviceText(context) }.getOrNull()
        var total = 0
        for (p in packs) {
            total += exportOutputs(p.pack, p.titleId, p, deviceText, devRoot).size
        }
        return total
    }

    private fun ensureWritableDir(dir: File): Boolean {
        if (dir.isDirectory) return dir.canWrite()
        val parent = dir.parentFile
        if (parent != null && parent.isDirectory && parent.canWrite()) {
            return dir.mkdirs() && dir.canWrite()
        }
        return false
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
            if (child.name == OUT_DIR) false
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
        dir.listFiles()?.filter { it.name != OUT_DIR }?.forEach { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val f = stack.removeFirst()
            if (f.lastModified() > max) max = f.lastModified()
            if (f.isDirectory) f.listFiles()?.forEach { stack.addLast(it) }
        }
        return max
    }

    private fun collectRuntimeBinaries(devDir: File): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        val searchDirs = listOf(
            File(devDir, "host"),
            File(devDir, "runtimes"),
            File(devDir, "runtime"),
            devDir,
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

    private fun syncTree(srcDir: File, dstDir: File, prefix: String, pulled: MutableList<String>) {
        if (!srcDir.isDirectory) return
        val children = srcDir.listFiles() ?: return
        for (child in children) {
            val target = File(dstDir, child.name)
            val rel = "$prefix/${child.name}"
            if (child.isDirectory) {
                syncTree(child, target, rel, pulled)
            } else if (child.isFile) {
                if (syncFileIfChanged(child, target)) pulled.add(rel)
            }
        }
    }

    private fun ensurePermissions(file: File, executable: Boolean) {
        if (!file.isFile) return
        runCatching {
            file.setReadable(true, false)
            if (executable) file.setExecutable(true, false)
        }
    }

    internal fun syncFileIfChanged(src: File, dst: File, executable: Boolean = false): Boolean {
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
        ensurePermissions(dst, executable)
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
