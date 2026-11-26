#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "qa_dev.h"

// Stub host dependencies when compiling qa_dev.c standalone for host unit tests.
double dm_log_elapsed(void) { return 12.345; }
void dm_gfx_passdump_next(long n) { (void)n; }

static uint64_t g_test_btn = 0;
static int32_t g_test_axes[4] = {0, 0, 0, 0};
void dm_hid_player1_state(uint64_t *out_btn, int32_t axes[4]) {
    if (out_btn) *out_btn = g_test_btn;
    if (axes) memcpy(axes, g_test_axes, sizeof g_test_axes);
}

static void fill_scene_grid(qa_grid16_t *g, uint8_t sky, uint8_t ground, uint8_t hill_delta) {
    for (int y = 0; y < 16; y++) {
        for (int x = 0; x < 16; x++) {
            int i = y * 16 + x;
            uint8_t base = y < 8 ? sky : ground;
            uint8_t hill = (y >= 6 && y <= 10 && x >= 4 && x <= 11) ? hill_delta : 0;
            uint8_t v = (uint8_t)(base + hill + (x % 3) * 2);
            g->r[i] = v;
            g->g[i] = (uint8_t)(v > 8 ? v - 4 : v);
            g->b[i] = (uint8_t)(y < 8 ? (v + 12 > 255 ? 255 : v + 12) : v);
            g->y[i] = (uint8_t)((54u * g->r[i] + 183u * g->g[i] + 19u * g->b[i]) >> 8);
        }
    }
}

static uint8_t *make_rgba_buffer(uint32_t w, uint32_t h, uint8_t r, uint8_t g, uint8_t b) {
    uint8_t *buf = malloc((size_t)w * h * 4);
    assert(buf != NULL);
    for (uint32_t y = 0; y < h; y++) {
        for (uint32_t x = 0; x < w; x++) {
            size_t off = ((size_t)y * w + x) * 4;
            uint8_t grad = (uint8_t)((x + y) % 16);
            buf[off + 0] = (uint8_t)(r + grad > 255 ? 255 : r + grad);
            buf[off + 1] = g;
            buf[off + 2] = b;
            buf[off + 3] = 255;
        }
    }
    return buf;
}

static void test_vshape_flicker_detector(void) {
    qa_grid16_t prev, mid, next, bright_sky, inverted;
    fill_scene_grid(&prev, 180, 40, 20);
    fill_scene_grid(&next, 180, 40, 20);   // scene matches N-1
    // Corrupt mid frame with a sharp flash
    fill_scene_grid(&mid, 250, 240, 5);
    double corr = 0.0;
    assert(qa_detect_vshape_flicker(&prev, &mid, &next, &corr) == 1);
    assert(corr > 0.90);

    // Panning dark ground -> bright sky: N-1 != N+1 and layout correlation drops
    fill_scene_grid(&bright_sky, 245, 220, 0);
    assert(qa_detect_vshape_flicker(&prev, &mid, &bright_sky, &corr) == 0);

    // Inverted scene layout (even with similar average brightness) must be rejected
    for (int i = 0; i < 256; i++) {
        inverted.r[i] = prev.r[255 - i];
        inverted.g[i] = prev.g[255 - i];
        inverted.b[i] = prev.b[255 - i];
        inverted.y[i] = prev.y[255 - i];
    }
    assert(qa_detect_vshape_flicker(&prev, &mid, &inverted, &corr) == 0);
}

static void test_cooldown_and_freespace_guard(const char *tmp_dir) {
    assert(qa_has_free_space_bytes(tmp_dir, 1024ULL) == 1);
    assert(qa_has_free_space_bytes(tmp_dir, 1000000ULL * 1024ULL * 1024ULL * 1024ULL) == 0);

    qa_grid16_t prev, mid, next;
    fill_scene_grid(&prev, 180, 40, 20);
    fill_scene_grid(&next, 180, 40, 20);
    fill_scene_grid(&mid, 250, 240, 5);

    double corr = 0.0;
    // Free space guard check inside qa_auto_flicker_should_trigger works against >= 2 GB.
    // On runners with >= 2 GB free, first triggers, then 2.0s cooldown blocks +1s and allows +2.1s.
    if (qa_has_free_space_bytes(tmp_dir, 2ULL * 1024 * 1024 * 1024)) {
        uint64_t t0 = 5000000000ULL;
        assert(qa_auto_flicker_should_trigger(&prev, &mid, &next, t0, &corr) == 1);
        assert(qa_auto_flicker_should_trigger(&prev, &mid, &next, t0 + 1000000000ULL, &corr) == 0);
        assert(qa_auto_flicker_should_trigger(&prev, &mid, &next, t0 + 2100000000ULL, &corr) == 1);
    }
}

static void test_input_record_replay_and_gating(const char *pack_dir, const char *dev_dir) {
    // Strictly inert when DM_DEV_QA != 1
    setenv("DM_DEV_QA", "0", 1);
    setenv("DM_DEV_DIR", dev_dir, 1);
    qa_dev_init(pack_dir, "010021601C496000");
    assert(qa_dev_enabled() == 0);
    assert(qa_input_start_record() == 0);
    assert(qa_flush_shaders() == 0);

    // Enable QA with temporary dev_dir and verify .rec round-trip + glitch dump + shader flush
    setenv("DM_DEV_QA", "1", 1);
    qa_dev_init(pack_dir, "010021601C496000");
    assert(qa_dev_enabled() == 1);

    // Record 2 frames into stress_test.rec and verify header + replay playback
    assert(qa_input_start_record() == 1);
    g_test_btn = 0x55ULL; g_test_axes[0] = 12345; g_test_axes[1] = -6789; g_test_axes[2] = 1000; g_test_axes[3] = -2000;
    qa_input_on_present_tick();
    g_test_btn = 0xAAULL; g_test_axes[0] = -32767; g_test_axes[1] = 32767; g_test_axes[2] = 0; g_test_axes[3] = 512;
    qa_input_on_present_tick();
    qa_input_stop();

    char rec_path[512];
    snprintf(rec_path, sizeof rec_path, "%s/stress_test.rec", dev_dir);
    FILE *rf = fopen(rec_path, "rb");
    assert(rf != NULL);
    fseek(rf, 0, SEEK_END);
    long rsz = ftell(rf);
    fseek(rf, 0, SEEK_SET);
    char magic[4] = {0};
    assert(fread(magic, 1, 4, rf) == 4);
    fclose(rf);
    assert(memcmp(magic, "NXR1", 4) == 0);
    assert(rsz == 16 + 2 * 32);

    assert(qa_input_start_replay() == 1);
    uint64_t rb = 0; int32_t rax[4] = {0};
    assert(qa_input_replay_sample(&rb, rax) == 1);
    assert(rb == 0x55ULL && rax[0] == 12345 && rax[1] == -6789 && rax[2] == 1000 && rax[3] == -2000);
    qa_input_on_present_tick();
    assert(qa_input_replay_sample(&rb, rax) == 1);
    assert(rb == 0xAAULL && rax[0] == -32767 && rax[1] == 32767 && rax[2] == 0 && rax[3] == 512);
    qa_input_on_present_tick();
    assert(qa_input_mode() == QA_INPUT_IDLE);

    // Shader flush test
    char cap_dir[512], cap_file[560];
    snprintf(cap_dir, sizeof cap_dir, "%s/captured_shaders", pack_dir);
    mkdir(cap_dir, 0755);
    snprintf(cap_file, sizeof cap_file, "%s/deadbeefcafebabe.bin", cap_dir);
    FILE *cf = fopen(cap_file, "wb");
    assert(cf != NULL); fwrite("DMSC", 1, 4, cf); fclose(cf);
    assert(qa_flush_shaders() == 1);
    snprintf(cap_file, sizeof cap_file, "%s/out/captured_shaders/deadbeefcafebabe.bin", dev_dir);
    assert(access(cap_file, R_OK) == 0);

    // Glitch dump test (3 PNGs + log_slice.txt + gpu_diff.txt)
    qa_set_present_frame(99);
    qa_log_record(1.0, "frame 99 ok");
    qa_vk_on_draw(99, 0x111ULL, 0x222ULL);
    qa_set_present_frame(100);
    qa_log_record(1.1, "frame 100 glitch!");
    qa_vk_on_pass(100, 0, 1280, 720, 37, 0, 126, 42, 0x111ULL, 0x333ULL);
    qa_vk_on_draw(100, 0x111ULL, 0x333ULL);
    qa_vk_on_dispatch(100, 0x444ULL);

    qa_raw_frame_t raw[3] = {
        {99, 16, 16, 0, 0, make_rgba_buffer(16, 16, 20, 30, 40)},
        {100, 16, 16, 0, 0, make_rgba_buffer(16, 16, 240, 10, 240)},
        {101, 16, 16, 0, 0, make_rgba_buffer(16, 16, 20, 30, 40)},
    };
    qa_submit_glitch_dump(raw, 100, "unit_test", 0.99);
    for (int w = 0; w < 100; w++) {
        char st[512];
        qa_status_json(st, sizeof st);
        if (strstr(st, "\"lastGlitchFrame\":100")) break;
        usleep(10000);
    }
    char gfile[560];
    snprintf(gfile, sizeof gfile, "%s/out/glitches/frame_100/frame_prev.png", dev_dir); assert(access(gfile, R_OK) == 0);
    snprintf(gfile, sizeof gfile, "%s/out/glitches/frame_100/frame_glitch.png", dev_dir); assert(access(gfile, R_OK) == 0);
    snprintf(gfile, sizeof gfile, "%s/out/glitches/frame_100/frame_next.png", dev_dir); assert(access(gfile, R_OK) == 0);
    snprintf(gfile, sizeof gfile, "%s/out/glitches/frame_100/log_slice.txt", dev_dir); assert(access(gfile, R_OK) == 0);
    snprintf(gfile, sizeof gfile, "%s/out/glitches/frame_100/gpu_diff.txt", dev_dir); assert(access(gfile, R_OK) == 0);
    FILE *df = fopen(gfile, "r");
    assert(df != NULL);
    char dtxt[1024] = {0};
    fread(dtxt, 1, sizeof dtxt - 1, df);
    fclose(df);
    assert(strstr(dtxt, "+0000000000000333") != NULL);
    assert(strstr(dtxt, "0000000000000444") != NULL);
}

int main(int argc, char **argv) {
    assert(argc >= 3);
    const char *pack_dir = argv[1];
    const char *dev_dir = argv[2];
    test_vshape_flicker_detector();
    test_cooldown_and_freespace_guard(dev_dir);
    test_input_record_replay_and_gating(pack_dir, dev_dir);

    // Also verify PNG encoder produces valid PNG signature
    char png_path[512];
    snprintf(png_path, sizeof png_path, "%s/test_frame.png", dev_dir);
    uint8_t *rgb = malloc(32 * 32 * 3);
    memset(rgb, 0x55, 32 * 32 * 3);
    assert(qa_write_png_rgb(png_path, rgb, 32, 32) == 1);
    free(rgb);
    FILE *pf = fopen(png_path, "rb");
    assert(pf != NULL);
    uint8_t sig[8] = {0};
    assert(fread(sig, 1, 8, pf) == 8);
    fclose(pf);
    assert(memcmp(sig, "\x89PNG\r\n\x1a\n", 8) == 0);

    // Verify downsample 16x16 on RGBA buffer
    uint8_t *rgba = make_rgba_buffer(64, 64, 200, 100, 50);
    qa_grid16_t ds;
    qa_downsample_16x16(rgba, 64, 64, 0, 0, &ds);
    free(rgba);
    assert(ds.r[0] >= 190 && ds.g[0] == 100 && ds.b[0] == 50);

    puts("qa_dev_harness: ALL PASSED");
    return 0;
}
