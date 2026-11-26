package com.nx.android.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 档案包的时效：这一版的档案包里有、玩家那一份里没有 —— 提示去导入；玩家自己已经有了，或者是压根没有的游戏，
 * 什么都不说。
 */
class ProfilesPackTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val tid = "0100000000010000"

    private fun pack() = ProfilesPack(setOf(tid), folder.root)

    @Test
    fun `the package has the game and the player's layer does not - the player's package is older`() {
        assertTrue(pack().stale(tid))
    }

    @Test
    fun `the player's layer has the game - nothing to say`() {
        File(folder.root, tid).mkdirs()
        assertFalse(pack().stale(tid))
    }

    @Test
    fun `a game the package does not have is not stale`() {
        assertFalse(pack().stale("01000FFFFFFFF000"))
    }

    @Test
    fun `no game read yet is not stale`() {
        assertFalse(pack().stale(""))
    }

    @Test
    fun `installBundledFromDirectory extracts built-in profiles and skips when stamp matches`() {
        val bundle = folder.newFolder("bundle-profiles")
        val layer = folder.newFolder("layer-profiles")
        val kcd1 = File(bundle, "010021601C496000").apply { mkdirs() }
        val kcd2 = File(bundle, "0100650018FE0000").apply { mkdirs() }
        File(kcd1, "game.tsv").writeText("title_id\t010021601C496000\n")
        File(kcd1, "shader_map.tsv").writeText("k1\tv1\n")
        File(kcd2, "game.tsv").writeText("title_id\t0100650018FE0000\n")
        File(bundle, "bundled.json").writeText(
            """{"ids":["010021601C496000","0100650018FE0000"],"sha256":"abc123"}"""
        )

        assertTrue(ProfilesPack.installBundledFromDirectory(bundle, layer))
        assertTrue(File(layer, "010021601C496000/game.tsv").isFile)
        assertTrue(File(layer, "010021601C496000/shader_map.tsv").isFile)
        assertTrue(File(layer, "0100650018FE0000/game.tsv").isFile)
        assertEquals("abc123", File(layer, "010021601C496000/${ProfilesPack.BUNDLED_STAMP_FILE}").readText().trim())

        // Re-running with matching stamp skips copying.
        assertFalse(ProfilesPack.installBundledFromDirectory(bundle, layer))

        // Bumping the bundle hash refreshes the files cleanly.
        File(kcd1, "game.tsv").writeText("title_id\t010021601C496000\nsmall_vpool_sparse\ton\n")
        File(bundle, "bundled.json").writeText(
            """{"ids":["010021601C496000","0100650018FE0000"],"sha256":"def456"}"""
        )
        assertTrue(ProfilesPack.installBundledFromDirectory(bundle, layer))
        assertTrue(File(layer, "010021601C496000/game.tsv").readText().contains("small_vpool_sparse"))
    }
}