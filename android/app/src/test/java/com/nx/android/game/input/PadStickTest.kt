package com.nx.android.game.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 触摸摇杆：原点是手指落下的那一点，落点周围有死区，边缘是满量程 */
class PadStickTest {

    /** 一根摇杆：中心在 (0.5, 0.8)，半径（半宽）是窗口高度的 0.08 */
    private val stick = PadControl(
        name = "stick_l", kind = "stick", btn = 0L,
        cx = 0.5f, cy = 0.8f, hw = 0.08f, hh = 0.08f, r = 0.04f, reach = 0.04f, s = 1f,
    )

    private val H = 1000f   // 窗口高度（像素）

    /** 按下并拖到 (x, y)（窗口高度为单位），返回游戏收到的那一份 */
    private fun drag(x: Float, y: Float, ctrls: List<PadControl> = listOf(stick)): PadOutput {
        val r = PadRouter()
        val out = PadOutput()
        val ids = intArrayOf(0)
        // 落下：手指按在 (x, y)
        r.route(PadRouter.ACTION_DOWN, 0, ids, floatArrayOf(x * H), floatArrayOf(y * H), 1, ctrls, H, out)
        // 拖到 (x, y)（同一处）：偏移为 0
        return out
    }

    /** 按下后拖到 (tx, ty)（窗口高度为单位） */
    private fun dragTo(downX: Float, downY: Float, tx: Float, ty: Float, ctrls: List<PadControl> = listOf(stick)): PadOutput {
        val r = PadRouter()
        val out = PadOutput()
        val ids = intArrayOf(0)
        r.route(PadRouter.ACTION_DOWN, 0, ids, floatArrayOf(downX * H), floatArrayOf(downY * H), 1, ctrls, H, out)
        r.route(PadRouter.ACTION_MOVE, 0, ids, floatArrayOf(tx * H), floatArrayOf(ty * H), 1, ctrls, H, out)
        return out
    }

    @Test
    fun `the finger's landing point is the origin`() {
        // 按在摇杆的边缘（右边），原地不动：没有偏移，就是零（原点是落点，不是控件中心）
        val out = dragTo(0.5f + 0.08f, 0.8f, 0.5f + 0.08f, 0.8f)
        assertEquals(0, out.lx)
        assertEquals(0, out.ly)
        // 从落点继续往右拖满一个半径：满量程（浮点下差一两个计数不算什么）
        val full = dragTo(0.5f + 0.08f, 0.8f, 0.5f + 0.16f, 0.8f)
        assertTrue(abs(full.lx - PadRouter.STICK_MAX) <= 2)
    }

    @Test
    fun `a thumb on the rim has output right away`() {
        // 按在摇杆中心偏右一点（离中心 0.04，摇杆半径 0.08），再往右拖 0.04：有输出
        val out = dragTo(0.5f + 0.04f, 0.8f, 0.5f + 0.08f, 0.8f)
        assertTrue("the stick moved to the right", out.lx > 0)
        // 偏移是 (0.08 - 0.04) / 0.08 = 0.5，出了死区后按剩下的行程重标定：(0.5 - 0.05) / 0.95
        val v = 0.5f
        val expect = (((v - PadRouter.STICK_DEAD) / (1f - PadRouter.STICK_DEAD)) * PadRouter.STICK_MAX).toInt()
        assertEquals(expect, out.lx)
    }

    @Test
    fun `the dead zone around the landing point is quiet`() {
        // 按在中心，拖动 0.02（摇杆半径 0.08 的 25%，在 5% 的死区外一点）
        val out = dragTo(0.5f, 0.8f, 0.5f + 0.001f, 0.8f)
        assertEquals(0, out.lx)
        assertEquals(0, out.ly)
    }

    @Test
    fun `the rim is the full deflection`() {
        val out = dragTo(0.5f, 0.8f, 0.5f + 0.08f, 0.8f)
        assertTrue(abs(out.lx - PadRouter.STICK_MAX) <= 2)
    }

    @Test
    fun `the right stick is finer at low speed`() {
        val right = stick.copy(name = "stick_r", cx = 0.8f)
        val leftOut = dragTo(0.5f, 0.8f, 0.5f + 0.04f, 0.8f)
        val rightOut = dragTo(0.8f, 0.8f, 0.8f + 0.04f, 0.8f, listOf(right))
        // 同样的偏移，右摇杆给得更少（低速更精细），但拉满时一样满
        assertTrue("the right stick gives less at low speed", rightOut.rx < leftOut.lx)
        val rightFull = dragTo(0.8f, 0.8f, 0.8f + 0.08f, 0.8f, listOf(right))
        val leftFull = dragTo(0.5f, 0.8f, 0.5f + 0.08f, 0.8f)
        assertEquals(leftFull.lx, rightFull.rx)
    }

    @Test
    fun `the knob shows the value the game gets`() {
        val r = PadRouter()
        val out = PadOutput()
        val ids = intArrayOf(0)
        r.route(PadRouter.ACTION_DOWN, 0, ids, floatArrayOf(0.5f * H), floatArrayOf(0.8f * H), 1, listOf(stick), H, out)
        r.route(PadRouter.ACTION_MOVE, 0, ids, floatArrayOf((0.5f + 0.04f) * H), floatArrayOf(0.8f * H), 1, listOf(stick), H, out)
        // 球心上的量就是游戏收到的那一份（按 STICK_MAX 折算回去）
        val fromGame = out.lx.toFloat() / PadRouter.STICK_MAX
        assertEquals(fromGame, r.knobs[0], 0.001f)
    }

    @Test
    fun `a touch outside the pad is the game's own`() {
        val out = drag(0.2f, 0.2f, ctrls = listOf(stick))
        assertEquals(0, out.buttons.toInt())
        assertEquals(0, out.lx)
        // 这次触摸交给了游戏（作为游戏的触点）
        assertEquals(1, out.ids.size)
    }

    @Test
    fun `dynamic half screen routes left half to left stick and second left touch to right stick camera`() {
        val r = PadRouter()
        val out = PadOutput()
        val W = 2000f
        val btn = PadControl("a", "button", 1L, 1.8f, 0.8f, 0.06f, 0.06f, 0.06f, 0.02f, 1f)
        val ctrls = listOf(stick, btn)

        // Finger 0 lands on open left half -> claims Left Stick
        r.route(PadRouter.ACTION_DOWN, 0, intArrayOf(0), floatArrayOf(200f), floatArrayOf(500f), 1, ctrls, H, out, W)
        assertEquals(0, r.leftStickPointerId)
        assertEquals(-1, r.rightStickPointerId)

        // Move finger 0 right -> drives lx
        r.route(PadRouter.ACTION_MOVE, 0, intArrayOf(0), floatArrayOf(300f), floatArrayOf(500f), 1, ctrls, H, out, W)
        assertTrue(out.lx > 0)
        assertEquals(0, out.rx)

        // Finger 1 lands on left half while Left Stick is held -> falls back to Right Stick (camera)
        r.route(PadRouter.ACTION_POINTER_DOWN, 1, intArrayOf(0, 1), floatArrayOf(300f, 350f), floatArrayOf(500f, 300f), 2, ctrls, H, out, W)
        assertEquals(0, r.leftStickPointerId)
        assertEquals(1, r.rightStickPointerId)

        // Move finger 1 -> drives rx without disturbing lx
        r.route(PadRouter.ACTION_MOVE, 0, intArrayOf(0, 1), floatArrayOf(300f, 450f), floatArrayOf(500f, 300f), 2, ctrls, H, out, W)
        assertTrue(out.lx > 0)
        assertTrue(out.rx > 0)

        // Lift finger 1 -> releases Right Stick only, Left Stick stays active
        r.route(PadRouter.ACTION_POINTER_UP, 1, intArrayOf(0, 1), floatArrayOf(300f, 450f), floatArrayOf(500f, 300f), 2, ctrls, H, out, W)
        assertEquals(0, r.leftStickPointerId)
        assertEquals(-1, r.rightStickPointerId)
        assertTrue(out.lx > 0)
        assertEquals(0, out.rx)

        // Discrete button still takes priority over right-half camera zone
        r.route(PadRouter.ACTION_POINTER_DOWN, 1, intArrayOf(0, 2), floatArrayOf(300f, 1.8f * H), floatArrayOf(500f, 0.8f * H), 2, ctrls, H, out, W)
        assertEquals(1L, out.buttons)
        assertEquals(-1, r.rightStickPointerId)
    }
}
