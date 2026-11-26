package com.nx.android.data

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.nx.android.domain.PlayerApiRevision
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import com.nx.android.core.storage.AppPaths
import com.nx.android.domain.environment.EnvironmentSetup
import com.nx.android.domain.environment.EnvPayload
import com.nx.android.domain.environment.GuestProcessLauncher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * The environment the porter runs in.
 *
 * The app is a shell: it unpacks the environment (the rootfs, the tool's tree and the assistant's engine, in
 * one archive) into its files folder, and runs the porter inside it. The guest programs are Linux binaries:
 * Android has no /lib and the guest is not a chroot, so they are started through the rootfs's dynamic loader,
 * and the programs the porter starts itself (the compiler, the .NET runtime) get their interpreter path
 * rewritten to the app's own rootfs.
 */
@Singleton
class Env @Inject constructor(@ApplicationContext private val ctx: Context, private val paths: AppPaths) : EnvironmentSetup, GuestProcessLauncher {

    companion object {
        const val TAG = "Nx2aEnv"

        /** The environment in one archive: the rootfs and the tool's tree (the compiler, the .NET runtime) */
        private const val PAYLOAD_ASSET = "payload.pkg"

        /** The archive's stamp: the app compares it with the one the environment was built with */
        private const val PAYLOAD_STAMP_ASSET = "payload-stamp"

        /** Where the environment remembers the stamp it was built with */
        private const val PAYLOAD_STAMP_FILE = ".nx2a-payload"

        /** The app's assets' manifest: it says how much space the environment needs */
        private const val MANIFEST_ASSET = "manifest.json"

        /** The room the environment needs on top of its own size (the archive's own copy is not counted) */
        private const val SPACE_MARGIN = 64L * 1024 * 1024
    }

    val files = paths.files
    val rootfs = paths.rootfs
    val tool = paths.tool
    val home = paths.home
    val nxHome = paths.nxHome
    val tmp = paths.tmp

    /** The assistant's own folder: it holds the engine, the models and the conversations. */
    val agent = paths.agent

    /** The engine's folder inside it: the environment's rebuild puts the engine there. */
    val agentRuntime = paths.agentRuntime

    /** 移植产出的数据包：移植器写在这里（NX2A_WORK 的旁边），游戏库也只在这里找包 */
    val ports = paths.ports

    /** 移植的工作区（NX2A_WORK）：解包、重写、着色器都在这里，一个游戏一个子目录 */
    val work = paths.work

    val loader = File(rootfs, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
    val python = File(rootfs, "usr/bin/python3.13")
    val interpPath = "${rootfs.absolutePath}/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"

    /** The environment is ready when the rootfs, the tool and the compiler are there */
    override val ready: Boolean
        get() = python.exists() && File(tool, "porter").isDirectory && File(tool, "toolchain/clang/bin/clang").exists()

    /**
     * The .NET runtime (the extractor and the shader translator are C# and run on it): where the porter looks
     * for it. It travels in the environment's archive, beside the compiler in the toolchain folder.
     */
    val dotnet: File get() = File(tool, "toolchain/dotnet/dotnet")

    /** The folder the runtime lives in: the porter's first choice is DOTNET_ROOT */
    private val dotnetHome: File get() = dotnet.parentFile ?: File(tool, "toolchain/dotnet")

    /** The environment every guest program gets */
    override fun guestEnv(): MutableMap<String, String> {
        home.mkdirs(); tmp.mkdirs(); nxHome.mkdirs()
        return mutableMapOf(
            "PATH" to "${tool}/toolchain/clang/bin:${tool}/toolchain/bin:${rootfs}/usr/bin",
            "HOME" to home.absolutePath,
            "TMPDIR" to tmp.absolutePath,
            "PYTHONHOME" to "${rootfs}/usr",
            "PYTHONPATH" to "${tool}/pylibs",
            "LD_LIBRARY_PATH" to "${rootfs}/usr/lib/aarch64-linux-gnu:${rootfs}/usr/lib",
            // The CA bundle the payload carries (rootfs_trim.py keeps it). The guest is not a chroot: OpenSSL's
            // built-in CA path names the phone's own files, not the rootfs's, so nothing would be trusted and
            // every TLS handshake would fail. This points the TLS libraries at the bundle in the rootfs.
            "SSL_CERT_FILE" to "${rootfs}/etc/ssl/certs/ca-certificates.crt",
            "NX2A_HOME" to nxHome.absolutePath,
            "NX2A_NDK" to "${tool}/toolchain/ndk",
            "NX2A_REPO" to tool.absolutePath,
            // the build tools' own folder: the pipeline needs its libraries (the toolchain's libshaderc, which glslc
            // needs). The layout here is the app's business, so the app says where it is.
            "NX2A_TOOLCHAIN" to "${tool}/toolchain",
            // the .NET runtime: the porter looks for it here first (DOTNET_ROOT), the tools it runs are the ones
            // in the tree next to it
            "DOTNET_ROOT" to dotnetHome.absolutePath,
            // nx_extract is built on the computer and shipped: the phone has no SDK to restore packages with
            // (no network), and its globalization mode makes the SDK itself crash
            "NX2A_NX_EXTRACT" to "${tool}/tools/nx_extract",
            // the build folder: Android does not let the app run a program on the shared storage, so the
            // port's own tools and the unpacked game live in the app's own folder
            "NX2A_WORK" to work.absolutePath,
            // the runtimes folder: the runtime a port builds goes here (one folder per title ID, with its
            // build marker), not into the pack — the launch loads it in place, no per-launch copy
            "NX2A_RUNTIMES" to paths.runtimes.absolutePath,
            "NX2A_PLAYER_API_REVISION" to PlayerApiRevision.CURRENT.toString(),
            "PYTHONUNBUFFERED" to "1",
            "PYTHONIOENCODING" to "utf-8",
            "DOTNET_SYSTEM_GLOBALIZATION_INVARIANT" to "1",
            "DOTNET_EnableWriteXorExecute" to "0",
            "DOTNET_CLI_TELEMETRY_OPTOUT" to "1",
            "DOTNET_SKIP_FIRST_TIME_EXPERIENCE" to "1",
            // the NuGet packages are restored from the app's own folder: the phone has no network
            "NUGET_PACKAGES" to "${nxHome}/nuget",
        )
    }

    /** Starts a guest program: Python through the loader, the tool itself as it is */
    @Synchronized
    override fun guest(args: List<String>, extra: Map<String, String>): Process {
        check(ready) { "The environment is not ready. Prepare it and retry." }
        return startGuest(args, tool, extra)
    }

    private fun startGuest(args: List<String>, cwd: File = tool, extra: Map<String, String> = emptyMap()): Process {
        val cmd = mutableListOf(loader.absolutePath, python.absolutePath)
        cmd.addAll(args)
        val pb = ProcessBuilder(cmd).directory(cwd)
        // one stream for both: a program that fills the pipe of the one we do not read would wait forever
        pb.redirectErrorStream(true)
        pb.environment().putAll(guestEnv())
        pb.environment().putAll(extra)
        return pb.start()
    }

    // ---- the environment: one archive, one stamp ----------------------------------------------------------------

    /**
     * The stamp the app's archive carries (empty when the app carries no environment at all).
     */
    private fun payloadStamp(): String = try {
        ctx.assets.open(PAYLOAD_STAMP_ASSET).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    } catch (e: Exception) {
        ""
    }

    /** The stamp the environment on this phone was built with (both root and tool trees must match). */
    private fun storedPayloadStamp(): String {
        val filesMark = File(files, PAYLOAD_STAMP_FILE)
        val toolMark = File(tool, PAYLOAD_STAMP_FILE)
        if (!filesMark.isFile || !toolMark.isFile) return ""
        if (!File(tool, "nxnative/dm/gfx/tools/shader_split.py").isFile) return ""
        val filesStamp = filesMark.readText().trim()
        val toolStamp = toolMark.readText().trim()
        return if (filesStamp.isNotEmpty() && filesStamp == toolStamp) filesStamp else ""
    }

    /** The environment remembers the stamp it was built with. */
    private fun storePayloadStamp(stamp: String) {
        runCatching { File(files, PAYLOAD_STAMP_FILE).writeText(stamp) }
        runCatching {
            if (tool.isDirectory) File(tool, PAYLOAD_STAMP_FILE).writeText(stamp)
        }
    }

    /**
     * Whether the environment has to be built again: the app brought another one.
     *
     * A phone that has no environment yet is not a rebuild: the first install is the guided setup's business.
     */
    override fun needsRebuild(): Boolean {
        return EnvPayload.stale(
            payloadStamp(), storedPayloadStamp(),
            rootfs.isDirectory || tool.isDirectory
        )
    }

    /**
     * Builds the environment again, with the archive the app carries.
     *
     * The old tree goes first and the stamp is written last: a build that was cut short (the process died) is
     * done again at the next start, because the stamp was not written. Only the environment is written: the
     * porter's own folder (the game profiles the player imported, the build products, the NuGet cache) and the
     * player's other things (the data packs, the assistant's folder, the history) are never touched.
     *
     * @param onProgress how much of it is done (0-100)
     */
    override fun rebuild(onProgress: (Int) -> Unit) {
        val want = payloadStamp()
        if (want.isEmpty()) return
        checkSpace()
        Log.i(TAG, "the environment is built again: $want")
        // The tree has to GO, not just be written over: the port compiles every source it finds (build.py globs
        // dm/android/*.c), so a file the new archive no longer has - a renamed one - would be compiled next to
        // its replacement, and the link would die on duplicate symbols.
        // These three folders and nothing else: the player's nx2android folder (nxHome) keeps what he has - the
        // imported profiles, the build products, the assistant's own folder - so a rebuild never takes the
        // assistant's data with it. The engine's own folder (agent/runtime) goes with the environment: it is
        // the environment's part of the assistant.
        for (dir in EnvPayload.replaced(rootfs, tool, agentRuntime)) dir.deleteRecursively()
        val bytes = payloadBytes()
        val total = if (bytes > 0) bytes else 1L
        ctx.assets.open(PAYLOAD_ASSET).use { raw ->
            GZIPInputStream(raw).use { gz ->
                Tar.unpack(gz, EnvPayload.roots(rootfs, tool, agent)) { written ->
                    onProgress(((written * 100) / total).toInt().coerceIn(0, 99))
                }
            }
        }
        onProgress(99)
        fixGlobalization()
        patchInterpreters()
        check()
        storePayloadStamp(want)
        // the markers of the old way (one stamp per part of the environment) go: they say nothing anymore
        for (old in listOf(".nx2a-build", ".nx2a-rootfs-version", ".nx2a-tool", ".nx2a-toolchain", ".nx2a-unpacked")) {
            File(files, old).delete()
        }
        onProgress(100)
        Log.i(TAG, "the environment is built")
    }

    /** How much space the environment needs on the phone: the archive's own size, unpacked. */
    private fun payloadBytes(): Long = try {
        ctx.assets.open(MANIFEST_ASSET).use { raw ->
            val text = raw.readBytes().toString(Charsets.UTF_8)
            JSONObject(text).optLong("payload_bytes", 0L)
        }
    } catch (e: Exception) {
        0L
    }

    /**
     * The space the environment needs: it is checked before the old tree goes, so the player is told what is
     * missing instead of getting a half-built environment.
     */
    private fun checkSpace() {
        val bytes = payloadBytes()
        if (bytes <= 0) return
        val free = try {
            StatFs(files.absolutePath).availableBytes
        } catch (e: Exception) {
            return
        }
        if (free < bytes + SPACE_MARGIN) {
            throw IOException(
                "Not enough space: the environment needs ${bytes / 1024 / 1024} MB, " +
                    "${free / 1024 / 1024} MB are free"
            )
        }
    }

    /**
     * Runs a guest program to see that the environment works: the loader, the rootfs's Python and the modules.
     * The failure says what is wrong, instead of leaving it to the next step.
     */
    private fun check() {
        val p = startGuest(listOf("-c", "import sys, lz4.block, capstone, os; " +
                "print('guest ok', sys.version.split()[0], 'storage', 'yes' if os.access('/sdcard', os.R_OK) else 'no')"))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        val err = p.errorStream.bufferedReader().readText()
        if (p.exitValue() != 0 || !out.contains("guest ok")) {
            Log.e(TAG, "the guest does not run (exit ${p.exitValue()}): ${(err + out).trim().take(400)}")
            throw IOException("the guest does not run (exit ${p.exitValue()}): ${(err + out).trim().take(400)}")
        }
        lastCheck = out.trim()
        Log.i(TAG, "check: ${out.trim()}")
    }

    /** What the check said (the screen shows it) */
    override var lastCheck: String = ""
        private set

    /**
     * Rewrites the interpreter of the programs that are started directly (the compiler, the .NET runtime): Android
     * has no /lib, and the rootfs the payload carries was built for another app's path.
     *
     * The assistant's engine is one of them: it comes out of the payload as it was built, so it needs the same
     * rewrite. It goes through here too, so a rebuild that brought a new engine brings a patched one.
     */
    override fun patchInterpreters() {
        Log.i(TAG, "python3.13 before patching: ${python.length()} bytes")
        val script = File(tool, "tools/patch_interp.py")
        if (!script.exists()) throw IOException("The interpreter patch tool is missing")
        val targets = listOf(File(tool, "toolchain"), File(rootfs, "usr/bin"), File(rootfs, "usr/sbin"),
                              File(tool, "tools/nx_extract"), File(agentRuntime, "pi"))
            .filter { it.exists() }
        nxHome.mkdirs()
        for (t in targets) {
            val log = File(nxHome, "patch-${t.name}.log")
            try {
                val pb = ProcessBuilder(loader.absolutePath, python.absolutePath,
                    script.absolutePath, t.absolutePath, interpPath)
                pb.directory(tool)
                pb.redirectErrorStream(true)
                pb.redirectOutput(log)
                pb.environment().putAll(guestEnv())
                val p = pb.start()
                val code = p.waitFor()
                val tail = log.readText().lines().lastOrNull { it.isNotBlank() } ?: ""
                if (code != 0) throw IOException("Interpreter patch failed for ${t.name}: exit $code")
                Log.i(TAG, "patch ${t.absolutePath}: exit $code $tail")
            } catch (e: Exception) {
                Log.e(TAG, "patch ${t.absolutePath}: ${e.message}")
                throw IOException("The build tools could not be prepared", e)
            }
            Log.i(TAG, "python3.13 after patching: ${python.length()} bytes")
        }
    }

    /**
     * The .NET runtime on the phone stops itself without ICU (the rootfs has none): the runtime config has to
     * declare the invariant mode, and the files below the folder have to be told.
     */
    private fun fixGlobalization() {
        val script = File(tool, "tools/fix_globalization.py")
        val sdk = File(tool, "toolchain/dotnet")
        if (!script.exists() || !sdk.isDirectory) return
        try {
            val p = startGuest(listOf(script.absolutePath, sdk.absolutePath))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            Log.i(TAG, "globalization: ${out.trim()}")
        } catch (e: Exception) {
            Log.e(TAG, "globalization: ${e.message}")
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EnvironmentDataModule {
    @Binds
    @Singleton
    abstract fun bindEnvironmentSetup(impl: Env): EnvironmentSetup

    @Binds
    @Singleton
    abstract fun bindGuestProcessLauncher(impl: Env): GuestProcessLauncher
}
