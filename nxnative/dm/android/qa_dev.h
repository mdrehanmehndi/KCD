#pragma once
#include <stddef.h>
#include <stdint.h>

// Developer QA subsystem (/sdcard/Download/nx2a_dev/<lowercase_title_id>/),
// strictly gated on DM_DEV_QA=1 + existing dev folder (zero cost otherwise).

enum {
    QA_INPUT_IDLE = 0,
    QA_INPUT_RECORD = 1,
    QA_INPUT_REPLAY = 2,
};

typedef struct {
    uint8_t r[256], g[256], b[256], y[256];
} qa_grid16_t;

typedef struct {
    uint64_t frame_no;
    uint32_t w, h;
    int bgra;
    int flip_y;
    uint8_t *rgba;   // heap-owned raw 4-bpp image; ownership transfers to writer
} qa_raw_frame_t;

void qa_dev_init(const char *pack_dir, const char *title_id);
int qa_dev_enabled(void);
const char *qa_dev_dir(void);

// Rolling 300-line log ring + present frame tracking
void qa_set_present_frame(uint64_t frame_no);
uint64_t qa_current_frame(void);
void qa_log_record(double t, const char *fmt, ...) __attribute__((format(printf, 2, 3)));

// Per-frame GPU metadata ring (last 8 frames)
void qa_vk_on_pass(uint64_t frame_no, int pass_idx, uint32_t w, uint32_t h,
                    uint32_t cfmt0, uint32_t cfmt1, uint32_t dfmt, uint32_t draws,
                    uint64_t vs, uint64_t fs);
void qa_vk_on_draw(uint64_t frame_no, uint64_t vs, uint64_t fs);
void qa_vk_on_dispatch(uint64_t frame_no, uint64_t cs);

// 16x16 grid downsampler & V-shape Auto-Flicker detector + guards
void qa_downsample_16x16(const uint8_t *px, uint32_t w, uint32_t h, int bgra, int flip_y, qa_grid16_t *out);
int qa_detect_vshape_flicker(const qa_grid16_t *prev, const qa_grid16_t *mid, const qa_grid16_t *next,
                             double *out_corr);
int qa_has_free_space_bytes(const char *path, uint64_t min_bytes);
int qa_auto_flicker_should_trigger(const qa_grid16_t *prev, const qa_grid16_t *mid, const qa_grid16_t *next,
                                   uint64_t now_ns, double *out_corr);
int qa_write_png_rgb(const char *path, const uint8_t *rgb, uint32_t w, uint32_t h);

// Manual / floating pill / auto glitch capture queue
int qa_flag_glitch(const char *source);
int qa_pending_glitch_target(uint64_t *out_target_frame, char *out_source, size_t cap);
void qa_clear_pending_glitch(uint64_t target_frame);
void qa_submit_glitch_dump(qa_raw_frame_t frames[3], uint64_t target_frame,
                           const char *reason, double layout_corr);

// 1-tap passdump & shader flush
int qa_trigger_passdump(void);
int qa_flush_shaders(void);

// Input record & replay (stress_test.rec)
int qa_input_start_record(void);
int qa_input_start_replay(void);
void qa_input_stop(void);
int qa_input_mode(void);
void qa_input_record_sample(uint64_t buttons, int32_t lx, int32_t ly, int32_t rx, int32_t ry);
int qa_input_replay_sample(uint64_t *buttons, int32_t axes[4]);
void qa_input_on_present_tick(void);

// Settings & status JSON for Dev drawer
void qa_set_auto_flicker(int on);
int qa_get_auto_flicker(void);
void qa_status_json(char *out, size_t cap);
