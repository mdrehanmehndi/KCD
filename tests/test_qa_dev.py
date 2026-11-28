"""Unit tests for Developer QA ring buffer math, PNG writer, .rec format, and Android rebrand wiring."""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path[:0] = [str(Path(__file__).resolve().parent)]
import host_cc   # noqa: E402

REPO = Path(__file__).resolve().parents[1]


class QaDevNativeTest(unittest.TestCase):
    def test_qa_dev_c_harness(self):
        compiler = shutil.which('cc') or shutil.which('gcc') or shutil.which('clang')
        if not compiler or not host_cc.posix():
            self.skipTest('No POSIX host C compiler available')

        with tempfile.TemporaryDirectory() as td:
            td_path = Path(td)
            inc = td_path / 'inc'
            inc.mkdir()
            (inc / 'game_profile.h').write_text('/* stub for host test */\n', encoding='utf-8')
            pack = td_path / 'pack'
            pack.mkdir()
            dev = td_path / 'dev'
            dev.mkdir()
            exe = td_path / 'qa_dev_harness'
            cmd = [
                compiler,
                '-std=gnu11',
                '-O2',
                '-Wall',
                '-Werror=implicit-function-declaration',
                '-DDM_ANDROID=1',
                f'-I{inc}',
                f'-I{REPO / "nxnative/dm/android"}',
                f'-I{REPO / "nxnative/dm/host"}',
                str(REPO / 'tests/qa_dev_harness.c'),
                str(REPO / 'nxnative/dm/android/qa_dev.c'),
                '-lz',
                '-lm',
                '-pthread',
                '-o',
                str(exe),
            ]
            comp = subprocess.run(cmd, capture_output=True, text=True)
            self.assertEqual(comp.returncode, 0, f'compile failed:\n{comp.stderr}')
            run = subprocess.run([str(exe), str(pack), str(dev)], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, f'harness failed:\n{run.stdout}\n{run.stderr}')
            self.assertIn('ALL PASSED', run.stdout)


class RebrandAndOverlayWiringTest(unittest.TestCase):
    def test_side_by_side_application_id_and_launch_wiring(self):
        gradle = (REPO / 'android/app/build.gradle.kts').read_text(encoding='utf-8')
        manifest = (REPO / 'android/app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
        dev_sync = (REPO / 'android/app/src/main/java/com/nx/android/data/DevSync.kt').read_text(encoding='utf-8')
        port_service = (REPO / 'android/app/src/main/java/com/nx/android/PortService.kt').read_text(encoding='utf-8')
        shortcuts = (REPO / 'android/app/src/main/java/com/nx/android/app/GameShortcuts.kt').read_text(encoding='utf-8')

        self.assertIn('namespace = "com.nx.android"', gradle)
        self.assertIn('applicationId = "com.warhome.kcd"', gradle)
        self.assertIn('${applicationId}.ACTION_DEV_LAUNCH', manifest)
        self.assertIn('android:scheme="kcdplay"', manifest)
        self.assertIn('${applicationId}.fileprovider', manifest)
        self.assertIn('${applicationId}.documents', manifest)
        self.assertIn('const val ACTION_LAUNCH = "${BuildConfig.APPLICATION_ID}.ACTION_DEV_LAUNCH"', dev_sync)
        self.assertIn('const val EXTRA_DEV_REPLAY = "dev_replay"', dev_sync)
        self.assertIn('const val ACTION_START = "${BuildConfig.APPLICATION_ID}.port.START"', port_service)
        self.assertIn('const val ACTION = "${BuildConfig.APPLICATION_ID}.action.LAUNCH_GAME"', shortcuts)

    def test_top_bar_brand_removed_and_brown_theme_and_dev_gating(self):
        app_root = (REPO / 'android/app/src/main/java/com/nx/android/app/AppRoot.kt').read_text(encoding='utf-8')
        theme = (REPO / 'android/app/src/main/java/com/nx/android/core/ui/theme/Theme.kt').read_text(encoding='utf-8')
        game_act = (REPO / 'android/app/src/main/java/com/nx/android/game/GameActivity.kt').read_text(encoding='utf-8')

        self.assertNotIn('R.drawable.ic_launcher_mark', app_root)
        self.assertIn('Color(0xFF4B3A26)', theme)
        self.assertIn('Color(0xFF120E0A)', theme)
        self.assertIn('Color(0xFFF3EAD8)', theme)
        self.assertIn('PrefsStore.DEV_AUTO_SYNC', game_act)
        self.assertIn('DM_DEV_QA=1', game_act)
        self.assertIn('DM_DEV_REPLAY=', game_act)
        self.assertIn('FloatingGlitchPill(', game_act)
