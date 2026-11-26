"""Tests for Kingdom Come: Deliverance fixes: static .pak CPCK extraction, KCD profiles, and SPIR-V output NaN/Inf sanitizer."""
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path[:0] = [
    str(Path(__file__).resolve().parent),
    str(REPO / 'nxnative/dm/android/tools'),
    str(REPO / 'nxnative/dm/gfx/tools'),
    str(REPO / 'nxnative/dm/games'),
]
import android_profile   # noqa: E402
import game              # noqa: E402
import host_cc           # noqa: E402
os.environ.setdefault('DM_GAME', '010021601C496000')
import shader_split      # noqa: E402


def _lzss_store(raw):
    """Encode raw bytes using uncompressed CPCK LZSS flag=1 blocks (0xff + 8 literals)."""
    out = bytearray()
    for i in range(0, len(raw), 8):
        blk = raw[i:i + 8]
        out.append((1 << len(blk)) - 1)
        out.extend(blk)
    return bytes(out)


def _fake_shader(stage, tag):
    """Build a valid (ctl, code) pair matching shader_split.py check() and glslc_stage()."""
    ncode = 0x100 if stage == 'cs' else 0x120
    nctl = 0x880
    ctl = bytearray(nctl)
    ctl[0:4] = shader_split.CTL_MAGIC
    struct.pack_into('<I', ctl, 0x20, nctl)
    struct.pack_into('<I', ctl, 0x704, ncode)
    if stage == 'cs':
        struct.pack_into('<4I', ctl, 0x748, 8, 4, 1, 0)
        code = bytearray(ncode)
        code[0:4] = shader_split.CS_MAGIC
        code[0x80:0x88] = tag
    else:
        st_idx = {'vs': 1, 'ps': 5}[stage]
        code = bytearray(ncode)
        code[0:4] = shader_split.GFX_MAGIC
        struct.pack_into('<I', code, 0x30, (st_idx << 10) | 1)
        code[0x80:0x88] = tag
    return bytes(ctl), bytes(code)


class CryPakShadersTest(unittest.TestCase):
    def test_extracts_all_ctl_occurrences_aligned_and_lzss(self):
        ctl1, code1 = _fake_shader('vs', b'SHAD_VS1')
        ctl2, code2 = _fake_shader('ps', b'SHAD_PS2')
        ctl3, code3 = _fake_shader('cs', b'SHAD_CS3')
        ctl4, code4 = _fake_shader('ps', b'SHAD_PS4')

        # Blob 1 (uncompressed CPCK entry): ctl+code adjacent, 64B-aligned ctl+code, then code-before-ctl (cs),
        # plus unaligned CTL_MAGIC (offset & 3 != 0) and CS_MAGIC before CTL_MAGIC with trailing non-magic data after ctl.
        ctl5, code5 = _fake_shader('vs', b'SHAD_VS5')
        ctl6, code6 = _fake_shader('cs', b'SHAD_CS6')
        blob1 = bytearray(ctl1 + code1)
        while len(blob1) & 63:
            blob1.append(0)
        blob1.extend(ctl2)
        blob1.extend(b'\x00' * 32)   # 32B padding between ctl2 and aligned code2
        blob1.extend(code2)
        blob1.extend(code3 + ctl3)   # compute code right before ctl3
        blob1.extend(b'\x01')        # force unaligned (offset & 3 != 0) vertex shader pair
        blob1.extend(code5 + ctl5)
        blob1.extend(code6 + ctl6 + b'\xff' * 0x200)   # trailing non-magic bytes after cs ctl6

        # Blob 2 (LZSS-compressed CPCK entry): another pixel shader
        blob2 = _lzss_store(ctl4 + code4)
        blob2_ent = struct.pack('<I', len(ctl4 + code4)) + blob2

        dir_off = 16
        data_off = dir_off + 2 * 12
        off1 = data_off
        off2 = off1 + len(blob1)
        cpck = bytearray(b'CPCK' + struct.pack('<III', 1, 2, dir_off))
        cpck.extend(struct.pack('<III', 0x11111111, len(blob1), off1))
        cpck.extend(struct.pack('<III', 0x22222222, (0x80 << 24) | len(blob2_ent), off2))
        cpck.extend(blob1)
        cpck.extend(blob2_ent)

        with tempfile.TemporaryDirectory() as td:
            pak = Path(td) / 'shaders.pak'
            with zipfile.ZipFile(pak, 'w') as zf:
                zf.writestr('multi.fxcb', bytes(cpck))
            found = []
            shader_split.cry_pak_shaders(str(pak), '/shaders.pak', found)

        self.assertEqual(len(found), 6, f'expected 6 shaders, got {[x[0] for x in found]}')
        self.assertEqual([x[1] for x in found], ['vs', 'ps', 'cs', 'vs', 'cs', 'ps'])
        self.assertEqual(found[4][3], code6)


class KcdProfilesTest(unittest.TestCase):
    def test_both_title_ids_present_and_valid(self):
        expected = {
            '010021601C496000': ('localization/japanese-part0.pak', 'wip'),
            '0100650018FE0000': ('localization/english-part0.pak', 'experimental'),
        }
        for tid, (marker, status) in expected.items():
            g = game.load(tid)
            ap = android_profile.load(tid)
            self.assertIsNotNone(ap, tid)
            self.assertEqual(g.get('romfs_marker'), marker)
            self.assertEqual(ap.status, status)
            self.assertEqual(ap.vulkan_driver, 'turnip')
            env = set(ap.env)
            for req in ('DM_VK_FRONT=2', 'DM_GPU_TURBO=1', 'DM_VK_CB_DRAWS=0', 'DM_VK_ASYNC_PIPE=0',
                        'DM_VK_ORDER_KEEP=0', 'DM_VK_NAN_SANITIZE=1', 'DM_VK_EXPAND_320X180=1',
                        'DM_FPS_TARGET=60', 'DM_ADAPTIVE_FPS=0'):
                self.assertIn(req, env, f'{tid}: missing {req}')
            cfg = ap.romfs_files.get('user.cfg', '')
            self.assertIn('r_DynamicResolution = 0', cfg)
            self.assertIn('r_ShadersAllowCompilation = 0', cfg)
            self.assertFalse(any(l.lstrip().startswith('--') for l in cfg.splitlines()), f'{tid} user.cfg uses -- comments')
            hdr = game.header(g)
            self.assertIn('#define DM_GAME_ORDER_KEEP 0', hdr)
            self.assertIn('#define DM_GAME_SMALL_VPOOL_SPARSE 1', hdr)

    def test_header_only_shader_map_triggers_derive_mode_for_us_eu(self):
        import importlib.util
        if importlib.util.find_spec('lz4') is None:
            self.skipTest('lz4 required for porter.pipeline')
        if str(REPO) not in sys.path:
            sys.path.insert(0, str(REPO))
        from porter.pipeline import Port
        self.assertTrue(Port._has_shader_rows(REPO / 'nxnative/dm/games/010021601C496000/shader_map.tsv'))
        self.assertFalse(Port._has_shader_rows(REPO / 'nxnative/dm/games/0100650018FE0000/shader_map.tsv'))


class SpvNanSanitizeTest(unittest.TestCase):
    def test_sanitizes_fragment_stores_and_alu_math_and_strips_relaxed_precision(self):
        cc = shutil.which('cc') or shutil.which('clang')
        if not cc:
            self.skipTest('no C compiler available')
        src = (REPO / 'nxnative/dm/android/gfx_vk_shader.c').read_text(encoding='utf-8')
        start = src.index('static uint32_t *spv_nan_sanitize(')
        end = src.index('static void spv_io_locs(', start)
        fn_code = src[start:end]

        harness = r'''
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
''' + fn_code + r'''
int main(void) {
    /* Synthetic FS SPIR-V:
       %1 = f32, %2 = vec4(f32), %3 = ptr_out(vec4), %4 = out_var(Location 0)
       %5 = internal fdiv result with RelaxedPrecision decoration (42)
       %6 = ptr_bi(vec4), %7 = builtin var (FragDepth-like BuiltIn 11)
       Function computes %5 = OpFDiv %2 %10 %11 and stores %5 to %4, and stores %5 to %7.
    */
    uint32_t w[] = {
        0x07230203u, 0x00010000u, 0u, 12u, 0u,
        (3u << 16) | 71u, 5u, 42u,             /* OpDecorate %5 RelaxedPrecision (42) */
        (4u << 16) | 71u, 7u, 11u, 15u,        /* OpDecorate %7 BuiltIn 15 */
        (3u << 16) | 22u, 1u, 32u,             /* %1 = OpTypeFloat 32 */
        (4u << 16) | 23u, 2u, 1u, 4u,          /* %2 = OpTypeVector %1 4 */
        (4u << 16) | 32u, 3u, 3u, 2u,          /* %3 = OpTypePointer Output %2 */
        (4u << 16) | 59u, 3u, 4u, 3u,          /* %4 = OpVariable %3 Output */
        (4u << 16) | 59u, 3u, 7u, 3u,          /* %7 = OpVariable %3 Output (BuiltIn) */
        (2u << 16) | 54u, 9u,                  /* OpFunction */
        (5u << 16) | 136u, 2u, 5u, 10u, 11u,   /* %5 = OpFDiv %2 %10 %11 */
        (3u << 16) | 62u, 4u, 5u,              /* OpStore %4 %5  (color output!) */
        (3u << 16) | 62u, 7u, 5u,              /* OpStore %7 %5  (BuiltIn, untouched!) */
        (1u << 16) | 56u                       /* OpFunctionEnd */
    };
    uint32_t nw = (uint32_t)(sizeof(w) / 4);
    uint32_t onw = 0;

    /* Compute stage (5) MUST also sanitize internal math (OpFDiv) */
    uint32_t *oc = spv_nan_sanitize(w, nw, 5, &onw);
    if (!oc) return 2;
    int c_relaxed = 0, c_fdiv = 0, c_isnan = 0, c_isinf = 0;
    for (uint32_t i = 5; i < onw;) {
        uint32_t n = oc[i] >> 16, op = oc[i] & 0xffff;
        if (!n || i + n > onw) return 10;
        if (op == 71 && oc[i + 1] == 5 && oc[i + 2] == 42) c_relaxed++;
        else if (op == 136) { c_fdiv++; if (oc[i + 2] == 5) return 11; }
        else if (op == 156) c_isnan++;
        else if (op == 157) c_isinf++;
        i += n;
    }
    free(oc);
    if (c_relaxed != 0 || c_fdiv != 1 || c_isnan != 1 || c_isinf != 1) {
        fprintf(stderr, "compute counts: relaxed=%d fdiv=%d isnan=%d isinf=%d\n", c_relaxed, c_fdiv, c_isnan, c_isinf);
        return 12;
    }

    /* Fragment stage (1) must sanitize both OpFDiv and non-builtin OpStore %4, stripping RelaxedPrecision on %5 */
    onw = 0;
    uint32_t *o = spv_nan_sanitize(w, nw, 1, &onw);
    if (!o) return 3;
    int relaxed = 0, fdiv = 0, isnan = 0, isinf = 0, stores_to_4 = 0, stores_to_7 = 0;
    for (uint32_t i = 5; i < onw;) {
        uint32_t n = o[i] >> 16, op = o[i] & 0xffff;
        if (!n || i + n > onw) return 4;
        if (op == 71 && o[i + 1] == 5 && o[i + 2] == 42) relaxed++;
        else if (op == 136) { fdiv++; if (o[i + 2] == 5) return 5; }
        else if (op == 156) isnan++;
        else if (op == 157) isinf++;
        else if (op == 62 && o[i + 1] == 4) { stores_to_4++; if (o[i + 2] == 5) return 6; }
        else if (op == 62 && o[i + 1] == 7) { stores_to_7++; if (o[i + 2] != 5) return 7; }
        i += n;
    }
    free(o);
    if (relaxed != 0 || fdiv != 1 || isnan != 2 || isinf != 2 || stores_to_4 != 1 || stores_to_7 != 1) {
        fprintf(stderr, "fragment counts: relaxed=%d fdiv=%d isnan=%d isinf=%d s4=%d s7=%d\n",
                relaxed, fdiv, isnan, isinf, stores_to_4, stores_to_7);
        return 8;
    }
    return 0;
}
'''
        with tempfile.TemporaryDirectory() as td:
            c = Path(td) / 'test_san.c'
            exe = Path(td) / 'test_san'
            c.write_text(harness, encoding='utf-8')
            comp = subprocess.run([cc, '-std=c11', '-O2', str(c), '-o', str(exe)], capture_output=True, text=True)
            self.assertEqual(comp.returncode, 0, comp.stderr)
            run = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)

    def test_unlzss_initializes_ring_with_spaces(self):
        tools_dir = str(REPO / 'nxnative/dm/gfx/tools')
        env = dict(os.environ, DM_GAME='010021601C496000')
        # VerifyCryEngine LZSS back-reference at offset 4078..4095 reads 0x20 (' ') not 0x00
        code = (
            'import sys; sys.argv=["shader_split.py"]; sys.path.insert(0, %r); '
            'import shader_split as ss; '
            'payload = bytes([0x00, 0xee, 0xf2]); '  # flag=0 -> back-ref off=0x0fee (4078), len=(2+3)=5
            'out = ss.unlzss(payload, 5); '
            'assert out == b"     ", f"got {out!r}"'
        ) % tools_dir
        res = subprocess.run([sys.executable, '-c', code], env=env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, res.stderr + res.stdout)


class VirtualBufferInitTest(unittest.TestCase):
    @unittest.skipUnless(host_cc.posix(), 'a POSIX host C compiler is required (<sys/mman.h>, pthread)')
    def test_virtual_pool_buffer_initialize_reserves_sparse_range_and_guards_null(self):
        cc = shutil.which('cc') or shutil.which('clang')
        if not cc:
            self.skipTest('no C compiler available')
        nvn_dir = REPO / 'nxnative/dm/gfx/nvn'
        src = (nvn_dir / 'hle_nvn.c').read_text(encoding='utf-8')
        vpool_start = src.index('enum { NVRUN = 1024, VPT_LEAF = 512 };')
        vpool_end = src.index('static void vpool_drop(', vpool_start)
        vpool_code = src[vpool_start:vpool_end]
        buf_start = src.index('typedef struct { NVNdevice *dev; const pool_t *pool;')
        buf_end = src.index('NVN(nvnBufferGetSize,', buf_start)
        buf_code = src[buf_start:buf_end]
        obj = (nvn_dir / 'nvn_obj.h').read_text(encoding='utf-8')
        pool_types = '\n'.join(
            line for line in obj.splitlines()
            if any('} ' + name + ';' in line for name in ('pool_builder_t', 'pool_t'))
        )

        harness = r'''
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include "nvn_protos.h"
#define MAGIC(a,b,c,d) ((uint32_t)(a)|(uint32_t)(b)<<8|(uint32_t)(c)<<16|(uint32_t)(d)<<24)
#define FITS(T,H) _Static_assert(sizeof(T)<=sizeof(H), "object exceeds NVN ABI")
#define LOG(...) ((void)0)
#define OLOG(...) ((void)0)
#define DM_GAME_APP_POOL 0xCD500000ULL
#define DM_GAME_SMALL_VPOOL_SPARSE 0
#define NVN(name,ret,...) static ret h_##name(__VA_ARGS__); static NVNT_##name entry_##name=h_##name; static ret h_##name(__VA_ARGS__)
''' + pool_types + '''
static uint8_t *vpool_reserve(pool_t *o);
static uint8_t *vpool_reserve_mode(pool_t *o, int force_sparse);
''' + buf_code + '\n' + vpool_code + r'''
int main(void) {
    /* 1. Unmapped 64 MB VIRTUAL pool even when small_vpool_sparse=0 must reserve on demand in BufferInitialize */
    NVNmemoryPool np = {0};
    pool_t *p = (pool_t *)&np;
    p->magic = MAGIC('P', 'O', 'O', 'L');
    p->flags = 0x221;
    p->size = 0x4000000ULL;
    p->mem = NULL; p->vp = NULL; p->vmode = 0;

    NVNbufferBuilder bb = {0};
    NVNbuffer buf = {0};
    entry_nvnBufferBuilderSetDefaults(&bb);
    entry_nvnBufferBuilderSetStorage(&bb, &np, 0x1000, 0x20000);
    assert(entry_nvnBufferInitialize(&buf, &bb) == 1);
    assert(p->mem != NULL && p->vp != NULL && p->vmode == 2);
    assert(entry_nvnBufferGetAddress(&buf) == (NVNbufferAddress)(p->mem + 0x1000));
    assert(entry_nvnBufferMap(&buf) == p->mem + 0x1000);

    /* 2. Non-virtual pool with no storage fails BufferInitialize cleanly and GetAddress/Map do not dereference NULL+0x8 */
    NVNmemoryPool badp = {0};
    pool_t *bp = (pool_t *)&badp;
    bp->magic = MAGIC('P', 'O', 'O', 'L');
    bp->flags = 0x21;
    bp->size = 0x10000;
    NVNbuffer badbuf = {0};
    entry_nvnBufferBuilderSetStorage(&bb, &badp, 0, 0x1000);
    assert(entry_nvnBufferInitialize(&badbuf, &bb) == 0);
    assert(entry_nvnBufferGetAddress(&badbuf) == 0);
    assert(entry_nvnBufferMap(&badbuf) == NULL);
    assert(entry_nvnBufferGetAddress(NULL) == 0);
    assert(entry_nvnBufferMap(NULL) == NULL);

    munmap(p->vp->base, p->vp->len);
    free(p->vp->pt); free(p->vp); free(g_vp);
    return 0;
}
'''
        with tempfile.TemporaryDirectory() as td:
            c = Path(td) / 'test_vbuf.c'
            exe = Path(td) / 'test_vbuf'
            c.write_text(harness, encoding='utf-8')
            comp = subprocess.run(
                [cc, '-std=gnu11', '-O2', '-I' + str(nvn_dir), '-I' + str(nvn_dir / 'include'), str(c), '-o', str(exe), '-lpthread'],
                capture_output=True, text=True,
            )
            self.assertEqual(comp.returncode, 0, comp.stderr)
            run = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)


class AppCaptureRebuildTest(unittest.TestCase):
    def test_pull_captures_in_app_mode_imports_and_preserves_captured_shaders(self):
        import importlib.util
        if importlib.util.find_spec('lz4') is None:
            self.skipTest('lz4 required for porter.pipeline')
        if str(REPO) not in sys.path:
            sys.path.insert(0, str(REPO))
        from types import SimpleNamespace
        from porter import datapack
        from porter.pipeline import Port

        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            port = Port.__new__(Port)
            port.o = SimpleNamespace(app=True, install=False, device=None, out=None)
            port.cfg = {}
            port.package = 'com.nx.p010021601c496000'
            port.aw = root / 'work/010021601C496000_build/android'
            port.work_root = root / 'work'
            port.g = game.load('010021601C496000')
            port.app_name = 'Kingdom Come: Deliverance - Royal Edition'

            data_dir = port.data_dir()
            cap_src = data_dir / 'captured_shaders'
            cap_src.mkdir(parents=True)
            good = cap_src / '0123456789abcdef.bin'
            good.write_bytes(b'DMSC' + b'\x00' * 16)
            (cap_src / 'bad_name.bin').write_bytes(b'DMSC')
            (cap_src / 'fedcba9876543210.bin').write_bytes(b'')   # empty file ignored

            port.pull_captures()
            pulled = port.aw / 'captured_shaders' / '0123456789abcdef.bin'
            self.assertTrue(pulled.is_file())
            self.assertEqual(pulled.read_bytes(), good.read_bytes())
            self.assertFalse((port.aw / 'captured_shaders' / 'bad_name.bin').exists())
            self.assertFalse((port.aw / 'captured_shaders' / 'fedcba9876543210.bin').exists())

            # Full datapack.build() rebuilds data_dir() while preserving captured_shaders/
            ap = android_profile.load('010021601C496000')
            patched = root / 'patched'
            patched.mkdir()
            (patched / 'main.nso').write_bytes(b'NSO0')
            shaders = root / 'shaders.dmsv'
            shaders.write_bytes(b'DMSV')
            datapack.build(
                data_dir,
                profile=ap,
                version='v0',
                display='1.0',
                patched=patched,
                romfs=None,
                shaders=shaders,
                pack_base=str(data_dir),
            )
            self.assertTrue((data_dir / 'captured_shaders' / '0123456789abcdef.bin').is_file())


if __name__ == '__main__':
    unittest.main()
