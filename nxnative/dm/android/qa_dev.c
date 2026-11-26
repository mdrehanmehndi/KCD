#include "qa_dev.h"
#include "dm.h"
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <time.h>
#include <unistd.h>
#include <zlib.h>

__attribute__((weak)) uint32_t vk_shader_bundle_count(void) { return 0; }
__attribute__((weak)) char g_vk_driver[32] = "VULKAN";
__attribute__((weak)) void dm_hid_player1_state(uint64_t *out_btn, int32_t axes[4]) {
    if (out_btn) *out_btn = 0;
    if (axes) memset(axes, 0, sizeof(int32_t) * 4);
}

static _Atomic int g_qa_active = 0;
static _Atomic int g_auto_flicker = 0;
static _Atomic uint64_t g_cur_frame = 0;
static char g_pack_dir[768] = "";
static char g_dev_dir[768] = "";
static char g_out_dir[800] = "";
static char g_title_lower[32] = "";

// ---- helpers ----

static uint64_t mono_ns_now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static void mkdir_p(const char *path) {
    char tmp[1024];
    size_t len = strlen(path);
    if (!len || len >= sizeof(tmp)) return;
    memcpy(tmp, path, len + 1);
    for (size_t i = 1; i < len; i++) {
        if (tmp[i] == '/') {
            tmp[i] = 0;
            mkdir(tmp, 0755);
            tmp[i] = '/';
        }
    }
    mkdir(tmp, 0755);
}

static int copy_file_atomic(const char *src, const char *dst) {
    struct stat sst, dst_st;
    if (stat(src, &sst) != 0 || !S_ISREG(sst.st_mode)) return 0;
    if (stat(dst, &dst_st) == 0 && sst.st_size == dst_st.st_size &&
        sst.st_mtime == dst_st.st_mtime) {
        return 0;   // unchanged
    }
    char part[1088];
    snprintf(part, sizeof part, "%s.part", dst);
    int in = open(src, O_RDONLY);
    if (in < 0) return 0;
    int out = open(part, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (out < 0) { close(in); return 0; }
    uint8_t buf[16384];
    ssize_t n;
    int ok = 1;
    while ((n = read(in, buf, sizeof buf)) > 0) {
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = write(out, buf + off, (size_t)(n - off));
            if (w <= 0) { ok = 0; break; }
            off += w;
        }
        if (!ok) break;
    }
    if (n < 0) ok = 0;
    close(in);
    if (close(out) != 0) ok = 0;
    if (!ok || rename(part, dst) != 0) {
        unlink(part);
        return 0;
    }
    return 1;
}

int qa_dev_enabled(void) { return atomic_load_explicit(&g_qa_active, memory_order_relaxed) != 0; }
const char *qa_dev_dir(void) { return g_dev_dir; }
void qa_set_present_frame(uint64_t frame_no) { atomic_store_explicit(&g_cur_frame, frame_no, memory_order_relaxed); }
uint64_t qa_current_frame(void) { return atomic_load_explicit(&g_cur_frame, memory_order_relaxed); }

// ---- 300-line rolling log buffer ----

enum { QA_LOG_LINES = 300, QA_LOG_LINE_LEN = 320 };
typedef struct {
    uint64_t frame_no;
    double elapsed_s;
    char text[QA_LOG_LINE_LEN];
} qa_log_entry_t;

static qa_log_entry_t g_logs[QA_LOG_LINES];
static uint32_t g_log_head = 0;
static pthread_mutex_t g_log_mu = PTHREAD_MUTEX_INITIALIZER;

void qa_log_record(double t, const char *fmt, ...) {
    if (!qa_dev_enabled()) return;
    va_list ap;
    pthread_mutex_lock(&g_log_mu);
    qa_log_entry_t *e = &g_logs[g_log_head++ % QA_LOG_LINES];
    e->frame_no = qa_current_frame();
    e->elapsed_s = t;
    va_start(ap, fmt);
    vsnprintf(e->text, sizeof e->text, fmt, ap);
    va_end(ap);
    pthread_mutex_unlock(&g_log_mu);
}

static size_t qa_snapshot_logs(uint64_t fmin, uint64_t fmax, qa_log_entry_t *out, size_t cap) {
    pthread_mutex_lock(&g_log_mu);
    uint32_t total = g_log_head < QA_LOG_LINES ? g_log_head : QA_LOG_LINES;
    uint32_t start = g_log_head > QA_LOG_LINES ? g_log_head - QA_LOG_LINES : 0;
    size_t n = 0;
    for (uint32_t i = 0; i < total && n < cap; i++) {
        const qa_log_entry_t *e = &g_logs[(start + i) % QA_LOG_LINES];
        if (e->frame_no >= fmin && e->frame_no <= fmax) {
            out[n++] = *e;
        }
    }
    pthread_mutex_unlock(&g_log_mu);
    return n;
}

// ---- Per-frame GPU metadata ring (last 8 frames) ----

enum {
    QA_META_FRAMES = 8,
    QA_MAX_PASSES = 64,
    QA_MAX_SHADERS = 64,
};

typedef struct {
    int pass_idx;
    uint32_t w, h, cfmt0, cfmt1, dfmt, draws;
    uint64_t vs, fs;
} qa_pass_rec_t;

typedef struct {
    uint64_t frame_no;
    uint32_t draws;
    uint32_t dispatches;
    uint32_t n_passes;
    uint32_t n_vs, n_fs, n_cs;
    qa_pass_rec_t passes[QA_MAX_PASSES];
    uint64_t vs_keys[QA_MAX_SHADERS];
    uint64_t fs_keys[QA_MAX_SHADERS];
    uint64_t cs_keys[QA_MAX_SHADERS];
} qa_frame_meta_t;

static qa_frame_meta_t g_meta[QA_META_FRAMES];
static pthread_mutex_t g_meta_mu = PTHREAD_MUTEX_INITIALIZER;

static qa_frame_meta_t *meta_slot(uint64_t frame_no) {
    qa_frame_meta_t *m = &g_meta[frame_no % QA_META_FRAMES];
    if (m->frame_no != frame_no) {
        memset(m, 0, sizeof *m);
        m->frame_no = frame_no;
    }
    return m;
}

static void add_unique_key(uint64_t *arr, uint32_t *cnt, uint32_t max, uint64_t key) {
    if (!key) return;
    for (uint32_t i = 0; i < *cnt; i++) {
        if (arr[i] == key) return;
    }
    if (*cnt < max) arr[(*cnt)++] = key;
}

void qa_vk_on_pass(uint64_t frame_no, int pass_idx, uint32_t w, uint32_t h,
                    uint32_t cfmt0, uint32_t cfmt1, uint32_t dfmt, uint32_t draws,
                    uint64_t vs, uint64_t fs) {
    if (!qa_dev_enabled()) return;
    pthread_mutex_lock(&g_meta_mu);
    qa_frame_meta_t *m = meta_slot(frame_no);
    if (m->n_passes < QA_MAX_PASSES) {
        m->passes[m->n_passes++] = (qa_pass_rec_t){pass_idx, w, h, cfmt0, cfmt1, dfmt, draws, vs, fs};
    }
    pthread_mutex_unlock(&g_meta_mu);
}

void qa_vk_on_draw(uint64_t frame_no, uint64_t vs, uint64_t fs) {
    if (!qa_dev_enabled()) return;
    pthread_mutex_lock(&g_meta_mu);
    qa_frame_meta_t *m = meta_slot(frame_no);
    m->draws++;
    add_unique_key(m->vs_keys, &m->n_vs, QA_MAX_SHADERS, vs);
    add_unique_key(m->fs_keys, &m->n_fs, QA_MAX_SHADERS, fs);
    pthread_mutex_unlock(&g_meta_mu);
}

void qa_vk_on_dispatch(uint64_t frame_no, uint64_t cs) {
    if (!qa_dev_enabled()) return;
    pthread_mutex_lock(&g_meta_mu);
    qa_frame_meta_t *m = meta_slot(frame_no);
    m->dispatches++;
    add_unique_key(m->cs_keys, &m->n_cs, QA_MAX_SHADERS, cs);
    pthread_mutex_unlock(&g_meta_mu);
}

static void meta_copy(uint64_t frame_no, qa_frame_meta_t *out, int *found) {
    pthread_mutex_lock(&g_meta_mu);
    qa_frame_meta_t *m = &g_meta[frame_no % QA_META_FRAMES];
    if (m->frame_no == frame_no) {
        *out = *m;
        *found = 1;
    } else {
        memset(out, 0, sizeof *out);
        out->frame_no = frame_no;
        *found = 0;
    }
    pthread_mutex_unlock(&g_meta_mu);
}

// ---- 16x16 grid & Auto-Flicker Detector ----

void qa_downsample_16x16(const uint8_t *px, uint32_t w, uint32_t h, int bgra, int flip_y, qa_grid16_t *out) {
    if (!px || !w || !h || !out) { if (out) memset(out, 0, sizeof *out); return; }
    for (int gy = 0; gy < 16; gy++) {
        uint32_t y0 = (uint32_t)gy * h / 16u;
        uint32_t y1 = (uint32_t)(gy + 1) * h / 16u;
        if (y1 <= y0) y1 = y0 + 1;
        uint32_t sy_step = (y1 - y0) > 4 ? (y1 - y0) / 4 : 1;
        for (int gx = 0; gx < 16; gx++) {
            uint32_t x0 = (uint32_t)gx * w / 16u;
            uint32_t x1 = (uint32_t)(gx + 1) * w / 16u;
            if (x1 <= x0) x1 = x0 + 1;
            uint32_t sx_step = (x1 - x0) > 4 ? (x1 - x0) / 4 : 1;
            uint32_t sr = 0, sg = 0, sb = 0, cnt = 0;
            for (uint32_t y = y0; y < y1 && y < h; y += sy_step) {
                uint32_t py = flip_y ? (h - 1u - y) : y;
                const uint8_t *row = px + (size_t)py * w * 4;
                for (uint32_t x = x0; x < x1 && x < w; x += sx_step) {
                    const uint8_t *p = row + (size_t)x * 4;
                    uint8_t r = p[bgra ? 2 : 0], g = p[1], b = p[bgra ? 0 : 2];
                    sr += r; sg += g; sb += b; cnt++;
                }
            }
            if (!cnt) cnt = 1;
            uint8_t r = (uint8_t)(sr / cnt), g = (uint8_t)(sg / cnt), b = (uint8_t)(sb / cnt);
            int idx = gy * 16 + gx;
            out->r[idx] = r; out->g[idx] = g; out->b[idx] = b;
            out->y[idx] = (uint8_t)((54u * r + 183u * g + 19u * b) >> 8);
        }
    }
}

static void grid_diff_metrics(const qa_grid16_t *a, const qa_grid16_t *b,
                              double *out_rgb, double *out_luma, double *out_spike_frac) {
    uint64_t s_rgb = 0, s_y = 0, spikes = 0;
    for (int i = 0; i < 256; i++) {
        int dr = abs((int)a->r[i] - (int)b->r[i]);
        int dg = abs((int)a->g[i] - (int)b->g[i]);
        int db = abs((int)a->b[i] - (int)b->b[i]);
        int dy = abs((int)a->y[i] - (int)b->y[i]);
        s_rgb += (uint64_t)(dr + dg + db);
        s_y += (uint64_t)dy;
        if (dy > 24 || (dr + dg + db) > 72) spikes++;
    }
    *out_rgb = (double)s_rgb / (3.0 * 256.0);
    *out_luma = (double)s_y / 256.0;
    *out_spike_frac = (double)spikes / 256.0;
}

static double grid_luma_corr(const qa_grid16_t *a, const qa_grid16_t *b) {
    double ma = 0, mb = 0;
    for (int i = 0; i < 256; i++) { ma += a->y[i]; mb += b->y[i]; }
    ma /= 256.0; mb /= 256.0;
    double num = 0, va = 0, vb = 0;
    for (int i = 0; i < 256; i++) {
        double da = a->y[i] - ma, db = b->y[i] - mb;
        num += da * db;
        va += da * da;
        vb += db * db;
    }
    double denom = sqrt(va * vb);
    if (denom < 1e-3) {
        // Near-flat images: layouts match only if brightnesses also match closely.
        return fabs(ma - mb) <= 8.0 ? 1.0 : 0.0;
    }
    return num / denom;
}

int qa_detect_vshape_flicker(const qa_grid16_t *prev, const qa_grid16_t *mid, const qa_grid16_t *next,
                             double *out_corr) {
    if (!prev || !mid || !next) return 0;
    double rgb_pm = 0, y_pm = 0, sp_pm = 0;
    double rgb_mn = 0, y_mn = 0, sp_mn = 0;
    double rgb_pn = 0, y_pn = 0, sp_pn = 0;
    grid_diff_metrics(prev, mid, &rgb_pm, &y_pm, &sp_pm);
    grid_diff_metrics(mid, next, &rgb_mn, &y_mn, &sp_mn);
    grid_diff_metrics(prev, next, &rgb_pn, &y_pn, &sp_pn);
    double d_pm = fmax(rgb_pm, y_pm);
    double d_mn = fmax(rgb_mn, y_mn);
    double d_pn = fmax(rgb_pn, y_pn);
    double corr = grid_luma_corr(prev, next);
    if (out_corr) *out_corr = corr;

    // (a) V-shape excursion and snap-back
    int pm_jump = (d_pm >= 14.0) || (sp_pm >= 0.12 && d_pm >= 8.0);
    int mn_jump = (d_mn >= 14.0) || (sp_mn >= 0.12 && d_mn >= 8.0);
    if (!pm_jump || !mn_jump) return 0;
    double min_leg = fmin(d_pm, d_mn);
    if (d_pn > 12.0 || d_pn > 0.35 * min_leg) return 0;
    // (b) Scene layouts match between N-1 and N+1 (rejects camera pans)
    if (corr < 0.85) return 0;
    return 1;
}

int qa_has_free_space_bytes(const char *path, uint64_t min_bytes) {
    if (!path || !*path) return 0;
    struct statvfs st;
    if (statvfs(path, &st) != 0) return 0;
    uint64_t avail = (uint64_t)st.f_bavail * (uint64_t)(st.f_frsize ? st.f_frsize : st.f_bsize);
    return avail >= min_bytes;
}

static _Atomic uint64_t g_last_auto_ns = 0;

int qa_auto_flicker_should_trigger(const qa_grid16_t *prev, const qa_grid16_t *mid, const qa_grid16_t *next,
                                   uint64_t now_ns, double *out_corr) {
    if (!qa_detect_vshape_flicker(prev, mid, next, out_corr)) return 0;
    uint64_t last = atomic_load(&g_last_auto_ns);
    if (last && now_ns >= last && (now_ns - last) < 2000000000ULL) return 0;
    const uint64_t two_gb = 2ULL * 1024ULL * 1024ULL * 1024ULL;
    if (!qa_has_free_space_bytes(g_dev_dir[0] ? g_dev_dir : ".", two_gb)) {
        LOG("[qa] auto-flicker suppressed: < 2 GB free space");
        return 0;
    }
    atomic_store(&g_last_auto_ns, now_ns);
    return 1;
}

// ---- Pure C PNG writer (zlib compress2 + crc32) ----

static void be32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24); p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8); p[3] = (uint8_t)v;
}

static int png_chunk(FILE *f, const char tag[4], const uint8_t *data, uint32_t len) {
    uint8_t hdr[8];
    be32(hdr, len);
    memcpy(hdr + 4, tag, 4);
    if (fwrite(hdr, 1, 8, f) != 8) return 0;
    if (len && fwrite(data, 1, len, f) != len) return 0;
    uLong crc = crc32(0L, (const Bytef *)tag, 4);
    if (len) crc = crc32(crc, (const Bytef *)data, len);
    uint8_t tail[4];
    be32(tail, (uint32_t)crc);
    return fwrite(tail, 1, 4, f) == 4;
}

int qa_write_png_rgb(const char *path, const uint8_t *rgb, uint32_t w, uint32_t h) {
    if (!path || !rgb || !w || !h) return 0;
    size_t row_bytes = (size_t)w * 3 + 1;
    size_t raw_len = row_bytes * (size_t)h;
    uint8_t *raw = malloc(raw_len);
    if (!raw) return 0;
    for (uint32_t y = 0; y < h; y++) {
        uint8_t *dst = raw + (size_t)y * row_bytes;
        dst[0] = 0;   // filter type 0
        memcpy(dst + 1, rgb + (size_t)y * w * 3, (size_t)w * 3);
    }
    uLong zlen = compressBound((uLong)raw_len);
    uint8_t *zbuf = malloc(zlen);
    if (!zbuf) { free(raw); return 0; }
    int zrc = compress2(zbuf, &zlen, raw, (uLong)raw_len, 1);
    free(raw);
    if (zrc != Z_OK) { free(zbuf); return 0; }

    char part[1100];
    snprintf(part, sizeof part, "%s.part", path);
    FILE *f = fopen(part, "wb");
    if (!f) { free(zbuf); return 0; }
    static const uint8_t sig[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    uint8_t ihdr[13] = {0};
    be32(ihdr, w); be32(ihdr + 4, h);
    ihdr[8] = 8; ihdr[9] = 2;   // 8-bit RGB
    int ok = fwrite(sig, 1, 8, f) == 8 &&
             png_chunk(f, "IHDR", ihdr, 13) &&
             png_chunk(f, "IDAT", zbuf, (uint32_t)zlen) &&
             png_chunk(f, "IEND", NULL, 0);
    if (fclose(f) != 0) ok = 0;
    free(zbuf);
    if (!ok || rename(part, path) != 0) { unlink(part); return 0; }
    return 1;
}

static uint8_t *rgba_to_rgb(const qa_raw_frame_t *rf) {
    if (!rf || !rf->rgba || !rf->w || !rf->h) return NULL;
    uint8_t *rgb = malloc((size_t)rf->w * rf->h * 3);
    if (!rgb) return NULL;
    for (uint32_t y = 0; y < rf->h; y++) {
        uint32_t sy = rf->flip_y ? (rf->h - 1u - y) : y;
        const uint8_t *src = rf->rgba + (size_t)sy * rf->w * 4;
        uint8_t *dst = rgb + (size_t)y * rf->w * 3;
        for (uint32_t x = 0; x < rf->w; x++) {
            dst[3 * x] = src[4 * x + (rf->bgra ? 2 : 0)];
            dst[3 * x + 1] = src[4 * x + 1];
            dst[3 * x + 2] = src[4 * x + (rf->bgra ? 0 : 2)];
        }
    }
    return rgb;
}

// ---- Glitch dump async worker + gpu_diff.txt ----

typedef struct qa_dump_job {
    qa_raw_frame_t frames[3];
    uint64_t target_frame;
    char reason[64];
    double layout_corr;
    qa_frame_meta_t prev_meta, mid_meta;
    int has_prev_meta, has_mid_meta;
    size_t n_logs;
    qa_log_entry_t *logs;
    struct qa_dump_job *next;
} qa_dump_job_t;

static pthread_mutex_t g_dump_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_dump_cv = PTHREAD_COND_INITIALIZER;
static qa_dump_job_t *g_dump_q = NULL;
static int g_writer_started = 0;
static _Atomic int64_t g_last_glitch_frame = -1;
static _Atomic uint64_t g_last_glitch_ms = 0;
static _Atomic uint32_t g_glitches_saved = 0;

static _Atomic uint64_t g_pending_frame = 0;
static char g_pending_source[64] = "";
static pthread_mutex_t g_pending_mu = PTHREAD_MUTEX_INITIALIZER;

static int key_in(uint64_t k, const uint64_t *arr, uint32_t n) {
    for (uint32_t i = 0; i < n; i++) if (arr[i] == k) return 1;
    return 0;
}

static void write_key_diffs(FILE *f, const char *label,
                            const uint64_t *a, uint32_t na,
                            const uint64_t *b, uint32_t nb) {
    fprintf(f, "%s (N=%u vs N-1=%u):", label, nb, na);
    int any = 0;
    for (uint32_t i = 0; i < nb; i++) {
        if (!key_in(b[i], a, na)) {
            fprintf(f, " +%016llx", (unsigned long long)b[i]);
            any = 1;
        }
    }
    for (uint32_t i = 0; i < na; i++) {
        if (!key_in(a[i], b, nb)) {
            fprintf(f, " -%016llx", (unsigned long long)a[i]);
            any = 1;
        }
    }
    if (!any) fprintf(f, " (identical)");
    fputc('\n', f);
}

static void write_gpu_diff(const char *path, const qa_dump_job_t *job) {
    FILE *f = fopen(path, "w");
    if (!f) return;
    const qa_frame_meta_t *p = &job->prev_meta, *m = &job->mid_meta;
    fprintf(f, "target_frame: %llu\n", (unsigned long long)job->target_frame);
    fprintf(f, "trigger: %s\n", job->reason[0] ? job->reason : "manual");
    fprintf(f, "layout_corr(N-1,N+1): %.4f\n", job->layout_corr);
    fprintf(f, "frames: prev=%llu glitch=%llu next=%llu\n\n",
            (unsigned long long)job->frames[0].frame_no,
            (unsigned long long)job->frames[1].frame_no,
            (unsigned long long)job->frames[2].frame_no);
    fprintf(f, "summary:\n");
    fprintf(f, "  passes:     N-1=%u, N=%u (delta %+lld)\n", p->n_passes, m->n_passes,
            (long long)m->n_passes - (long long)p->n_passes);
    fprintf(f, "  draws:      N-1=%u, N=%u (delta %+lld)\n", p->draws, m->draws,
            (long long)m->draws - (long long)p->draws);
    fprintf(f, "  dispatches: N-1=%u, N=%u (delta %+lld)\n\n", p->dispatches, m->dispatches,
            (long long)m->dispatches - (long long)p->dispatches);

    write_key_diffs(f, "VS shaders", p->vs_keys, p->n_vs, m->vs_keys, m->n_vs);
    write_key_diffs(f, "FS shaders", p->fs_keys, p->n_fs, m->fs_keys, m->n_fs);
    write_key_diffs(f, "CS shaders", p->cs_keys, p->n_cs, m->cs_keys, m->n_cs);

    fprintf(f, "\npasses in frame N (%u recorded):\n", m->n_passes);
    for (uint32_t i = 0; i < m->n_passes; i++) {
        const qa_pass_rec_t *rp = &m->passes[i];
        fprintf(f, "  #%02d %ux%u c0=%u c1=%u df=%u draws=%u vs=%016llx fs=%016llx\n",
                rp->pass_idx, rp->w, rp->h, rp->cfmt0, rp->cfmt1, rp->dfmt, rp->draws,
                (unsigned long long)rp->vs, (unsigned long long)rp->fs);
    }

    fprintf(f, "\nactive env.txt + DM_VK_* flags:\n");
    char envpath[860];
    snprintf(envpath, sizeof envpath, "%s/env.txt", g_pack_dir);
    FILE *ef = fopen(envpath, "r");
    if (ef) {
        char line[512];
        while (fgets(line, sizeof line, ef)) {
            if (line[0] && line[0] != '#' && line[0] != '\n') fputs("  ", f), fputs(line, f);
        }
        fclose(ef);
    }
    static const char *const live_vars[] = {
        "DM_VK_NAN_SANITIZE", "DM_VK_EXPAND_320X180", "DM_VK_CB_DRAWS",
        "DM_VK_SUBGROUP_PIN", "DM_VK_C2_FAST", "DM_VK_GS_SPLIT", NULL
    };
    for (int i = 0; live_vars[i]; i++) {
        const char *v = getenv(live_vars[i]);
        if (v && *v) fprintf(f, "  [live] %s=%s\n", live_vars[i], v);
    }
    fclose(f);
}

static void *qa_writer_main(void *arg) {
    (void)arg;
    for (;;) {
        pthread_mutex_lock(&g_dump_mu);
        while (!g_dump_q) pthread_cond_wait(&g_dump_cv, &g_dump_mu);
        qa_dump_job_t *job = g_dump_q;
        g_dump_q = job->next;
        pthread_mutex_unlock(&g_dump_mu);

        char dir[1024];
        snprintf(dir, sizeof dir, "%s/glitches/frame_%llu", g_out_dir, (unsigned long long)job->target_frame);
        mkdir_p(dir);

        static const char *const names[3] = {"frame_prev.png", "frame_glitch.png", "frame_next.png"};
        for (int i = 0; i < 3; i++) {
            if (!job->frames[i].rgba) continue;
            uint8_t *rgb = rgba_to_rgb(&job->frames[i]);
            if (rgb) {
                char p[1100];
                snprintf(p, sizeof p, "%s/%s", dir, names[i]);
                qa_write_png_rgb(p, rgb, job->frames[i].w, job->frames[i].h);
                free(rgb);
            }
            free(job->frames[i].rgba);
        }

        char lpath[1100];
        snprintf(lpath, sizeof lpath, "%s/log_slice.txt", dir);
        FILE *lf = fopen(lpath, "w");
        if (lf) {
            for (size_t i = 0; i < job->n_logs; i++) {
                fprintf(lf, "[F%llu %9.3fs] %s\n",
                        (unsigned long long)job->logs[i].frame_no,
                        job->logs[i].elapsed_s, job->logs[i].text);
            }
            fclose(lf);
        }
        free(job->logs);

        char gpath[1100];
        snprintf(gpath, sizeof gpath, "%s/gpu_diff.txt", dir);
        write_gpu_diff(gpath, job);

        atomic_store(&g_last_glitch_frame, (int64_t)job->target_frame);
        atomic_store(&g_last_glitch_ms, mono_ns_now() / 1000000ULL);
        atomic_fetch_add(&g_glitches_saved, 1);
        LOG("[qa] glitch bundle saved -> %s (%s)", dir, job->reason);
        free(job);
    }
    return NULL;
}

void qa_submit_glitch_dump(qa_raw_frame_t frames[3], uint64_t target_frame,
                           const char *reason, double layout_corr) {
    if (!qa_dev_enabled()) {
        for (int i = 0; i < 3; i++) free(frames[i].rgba);
        return;
    }
    qa_dump_job_t *job = calloc(1, sizeof *job);
    if (!job) {
        for (int i = 0; i < 3; i++) free(frames[i].rgba);
        return;
    }
    for (int i = 0; i < 3; i++) job->frames[i] = frames[i];
    job->target_frame = target_frame;
    snprintf(job->reason, sizeof job->reason, "%s", reason ? reason : "manual");
    job->layout_corr = layout_corr;
    if (target_frame > 0) meta_copy(target_frame - 1, &job->prev_meta, &job->has_prev_meta);
    meta_copy(target_frame, &job->mid_meta, &job->has_mid_meta);
    uint64_t fmin = target_frame >= 2 ? target_frame - 2 : 0;
    job->logs = calloc(QA_LOG_LINES, sizeof(qa_log_entry_t));
    if (job->logs) job->n_logs = qa_snapshot_logs(fmin, target_frame + 2, job->logs, QA_LOG_LINES);

    pthread_mutex_lock(&g_dump_mu);
    if (!g_writer_started) {
        pthread_t th;
        if (pthread_create(&th, NULL, qa_writer_main, NULL) == 0) {
            pthread_detach(th);
            g_writer_started = 1;
        }
    }
    job->next = g_dump_q;
    g_dump_q = job;
    pthread_cond_signal(&g_dump_cv);
    pthread_mutex_unlock(&g_dump_mu);
}

int qa_flag_glitch(const char *source) {
    if (!qa_dev_enabled()) return -1;
    uint64_t target = qa_current_frame() + 1;
    pthread_mutex_lock(&g_pending_mu);
    atomic_store(&g_pending_frame, target);
    snprintf(g_pending_source, sizeof g_pending_source, "%s", source ? source : "manual");
    pthread_mutex_unlock(&g_pending_mu);
    return (int)target;
}

int qa_pending_glitch_target(uint64_t *out_target_frame, char *out_source, size_t cap) {
    uint64_t tgt = atomic_load(&g_pending_frame);
    if (!tgt) return 0;
    pthread_mutex_lock(&g_pending_mu);
    tgt = atomic_load(&g_pending_frame);
    if (tgt) {
        if (out_target_frame) *out_target_frame = tgt;
        if (out_source && cap) snprintf(out_source, cap, "%s", g_pending_source);
    }
    pthread_mutex_unlock(&g_pending_mu);
    return tgt != 0;
}

void qa_clear_pending_glitch(uint64_t target_frame) {
    pthread_mutex_lock(&g_pending_mu);
    if (atomic_load(&g_pending_frame) == target_frame) {
        atomic_store(&g_pending_frame, 0);
        g_pending_source[0] = 0;
    }
    pthread_mutex_unlock(&g_pending_mu);
}

// ---- Passdump & Captured Shader Flush ----

int qa_trigger_passdump(void) {
    if (!qa_dev_enabled()) return 0;
    char dir[1024];
    snprintf(dir, sizeof dir, "%s/passdump/frame_%llu", g_out_dir, (unsigned long long)qa_current_frame() + 1);
    mkdir_p(dir);
    setenv("DM_VK_PASSDUMP_DIR", dir, 1);
    setenv("DM_VK_PASSDUMP", "1", 1);
    void dm_gfx_passdump_next(long n);
    dm_gfx_passdump_next(1);
    LOG("[qa] passdump armed -> %s", dir);
    return 1;
}

int qa_flush_shaders(void) {
    if (!qa_dev_enabled() || !g_pack_dir[0]) return 0;
    char src_dir[900], dst_dir[900];
    snprintf(src_dir, sizeof src_dir, "%s/captured_shaders", g_pack_dir);
    snprintf(dst_dir, sizeof dst_dir, "%s/captured_shaders", g_out_dir);
    DIR *d = opendir(src_dir);
    if (!d) return 0;
    mkdir_p(dst_dir);
    int copied = 0;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        size_t len = strlen(de->d_name);
        if (len < 5 || strcmp(de->d_name + len - 4, ".bin") != 0) continue;
        char s[1200], t[1200];
        snprintf(s, sizeof s, "%s/%s", src_dir, de->d_name);
        snprintf(t, sizeof t, "%s/%s", dst_dir, de->d_name);
        copied += copy_file_atomic(s, t);
    }
    closedir(d);
    LOG("[qa] flushed %d captured shader(s) -> %s", copied, dst_dir);
    return copied;
}

// ---- Input Record & Replay (stress_test.rec) ----
// Format: Header "NXR1" + u32 version(1) + u32 flags(0) + u32 fps(60) = 16 bytes
// Entry:  u32 dt_ms + u32 reserved + u64 buttons + i32 lx + i32 ly + i32 rx + i32 ry = 32 bytes

typedef struct __attribute__((packed)) {
    char magic[4];
    uint32_t version, flags, fps;
} qa_rec_hdr_t;

typedef struct __attribute__((packed)) {
    uint32_t dt_ms, reserved;
    uint64_t buttons;
    int32_t lx, ly, rx, ry;
} qa_rec_entry_t;

_Static_assert(sizeof(qa_rec_hdr_t) == 16, "qa_rec_hdr");
_Static_assert(sizeof(qa_rec_entry_t) == 32, "qa_rec_entry");

static _Atomic int g_in_mode = QA_INPUT_IDLE;
static FILE *g_rec_fp = NULL;
static qa_rec_entry_t *g_replay_buf = NULL;
static uint32_t g_rec_frames = 0;
static uint32_t g_replay_total = 0;
static uint32_t g_replay_idx = 0;
static uint64_t g_last_sample_ms = 0;
static pthread_mutex_t g_in_mu = PTHREAD_MUTEX_INITIALIZER;

static void stop_input_locked(void) {
    if (g_rec_fp) { fflush(g_rec_fp); fclose(g_rec_fp); g_rec_fp = NULL; }
    free(g_replay_buf); g_replay_buf = NULL;
    g_replay_idx = 0;
    atomic_store(&g_in_mode, QA_INPUT_IDLE);
}

int qa_input_mode(void) { return atomic_load_explicit(&g_in_mode, memory_order_relaxed); }

int qa_input_start_record(void) {
    if (!qa_dev_enabled()) return 0;
    pthread_mutex_lock(&g_in_mu);
    stop_input_locked();
    char path[900];
    snprintf(path, sizeof path, "%s/stress_test.rec", g_dev_dir);
    FILE *f = fopen(path, "wb");
    if (!f) { pthread_mutex_unlock(&g_in_mu); return 0; }
    qa_rec_hdr_t hdr = {{'N', 'X', 'R', '1'}, 1, 0, 60};
    if (fwrite(&hdr, sizeof hdr, 1, f) != 1) { fclose(f); pthread_mutex_unlock(&g_in_mu); return 0; }
    g_rec_fp = f;
    g_rec_frames = 0;
    g_last_sample_ms = mono_ns_now() / 1000000ULL;
    atomic_store(&g_in_mode, QA_INPUT_RECORD);
    pthread_mutex_unlock(&g_in_mu);
    LOG("[qa] input recording started -> %s", path);
    return 1;
}

int qa_input_start_replay(void) {
    if (!qa_dev_enabled()) return 0;
    pthread_mutex_lock(&g_in_mu);
    stop_input_locked();
    char path[900];
    snprintf(path, sizeof path, "%s/stress_test.rec", g_dev_dir);
    FILE *f = fopen(path, "rb");
    if (!f) { pthread_mutex_unlock(&g_in_mu); return 0; }
    qa_rec_hdr_t hdr;
    if (fread(&hdr, sizeof hdr, 1, f) != 1 || memcmp(hdr.magic, "NXR1", 4) != 0 || hdr.version != 1) {
        fclose(f);
        pthread_mutex_unlock(&g_in_mu);
        LOG("[RED] [qa] invalid stress_test.rec header: %s", path);
        return 0;
    }
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, sizeof hdr, SEEK_SET);
    if (sz <= (long)sizeof hdr) { fclose(f); pthread_mutex_unlock(&g_in_mu); return 0; }
    uint32_t n = (uint32_t)((sz - (long)sizeof hdr) / (long)sizeof(qa_rec_entry_t));
    if (!n) { fclose(f); pthread_mutex_unlock(&g_in_mu); return 0; }
    qa_rec_entry_t *buf = malloc((size_t)n * sizeof(qa_rec_entry_t));
    if (!buf || fread(buf, sizeof(qa_rec_entry_t), n, f) != n) {
        free(buf); fclose(f); pthread_mutex_unlock(&g_in_mu); return 0;
    }
    fclose(f);
    g_replay_buf = buf;
    g_replay_total = n;
    g_replay_idx = 0;
    atomic_store(&g_in_mode, QA_INPUT_REPLAY);
    pthread_mutex_unlock(&g_in_mu);
    LOG("[qa] input replay started (%u frames) <- %s", n, path);
    return 1;
}

void qa_input_stop(void) {
    pthread_mutex_lock(&g_in_mu);
    int prev = atomic_load(&g_in_mode);
    stop_input_locked();
    pthread_mutex_unlock(&g_in_mu);
    if (prev != QA_INPUT_IDLE) LOG("[qa] input %s stopped", prev == QA_INPUT_RECORD ? "recording" : "replay");
}

void qa_input_record_sample(uint64_t buttons, int32_t lx, int32_t ly, int32_t rx, int32_t ry) {
    if (qa_input_mode() != QA_INPUT_RECORD) return;
    pthread_mutex_lock(&g_in_mu);
    if (atomic_load(&g_in_mode) == QA_INPUT_RECORD && g_rec_fp) {
        uint64_t now = mono_ns_now() / 1000000ULL;
        uint32_t dt = g_last_sample_ms && now >= g_last_sample_ms ? (uint32_t)(now - g_last_sample_ms) : 16;
        g_last_sample_ms = now;
        qa_rec_entry_t ent = {dt, 0, buttons, lx, ly, rx, ry};
        fwrite(&ent, sizeof ent, 1, g_rec_fp);
        g_rec_frames++;
        if ((g_rec_frames & 63u) == 0) fflush(g_rec_fp);
    }
    pthread_mutex_unlock(&g_in_mu);
}

int qa_input_replay_sample(uint64_t *buttons, int32_t axes[4]) {
    if (qa_input_mode() != QA_INPUT_REPLAY) return 0;
    pthread_mutex_lock(&g_in_mu);
    if (atomic_load(&g_in_mode) == QA_INPUT_REPLAY && g_replay_buf && g_replay_idx < g_replay_total) {
        const qa_rec_entry_t *e = &g_replay_buf[g_replay_idx];
        *buttons = e->buttons;
        axes[0] = e->lx; axes[1] = e->ly; axes[2] = e->rx; axes[3] = e->ry;
        pthread_mutex_unlock(&g_in_mu);
        return 1;
    }
    pthread_mutex_unlock(&g_in_mu);
    return 0;
}

void qa_input_on_present_tick(void) {
    int mode = qa_input_mode();
    if (mode == QA_INPUT_RECORD) {
        uint64_t b = 0; int32_t ax[4] = {0};
        if (dm_hid_player1_state) dm_hid_player1_state(&b, ax);
        qa_input_record_sample(b, ax[0], ax[1], ax[2], ax[3]);
        return;
    }
    if (mode != QA_INPUT_REPLAY) return;
    int finished = 0;
    uint32_t total = 0;
    pthread_mutex_lock(&g_in_mu);
    if (atomic_load(&g_in_mode) == QA_INPUT_REPLAY && g_replay_buf) {
        if (g_replay_idx + 1 < g_replay_total) {
            g_replay_idx++;
        } else {
            total = g_replay_total;
            stop_input_locked();
            finished = 1;
        }
    }
    pthread_mutex_unlock(&g_in_mu);
    if (finished) LOG("[qa] input replay completed (%u frames)", total);
}

// ---- Settings, Init & JSON Status ----

void qa_set_auto_flicker(int on) { atomic_store(&g_auto_flicker, on != 0); }
int qa_get_auto_flicker(void) { return atomic_load_explicit(&g_auto_flicker, memory_order_relaxed); }

static int count_captured_shaders(void) {
    if (!g_pack_dir[0]) return 0;
    char dir[900];
    snprintf(dir, sizeof dir, "%s/captured_shaders", g_pack_dir);
    DIR *d = opendir(dir);
    if (!d) return 0;
    int n = 0;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        size_t len = strlen(de->d_name);
        if (len >= 5 && strcmp(de->d_name + len - 4, ".bin") == 0) n++;
    }
    closedir(d);
    return n;
}

void qa_dev_init(const char *pack_dir, const char *title_id) {
    const char *gate = getenv("DM_DEV_QA");
    if (!gate || atoi(gate) <= 0 || !pack_dir || !*pack_dir || !title_id || strlen(title_id) != 16) {
        atomic_store(&g_qa_active, 0);
        return;
    }
    snprintf(g_pack_dir, sizeof g_pack_dir, "%s", pack_dir);
    for (int i = 0; i < 16; i++) g_title_lower[i] = (char)tolower((unsigned char)title_id[i]);
    g_title_lower[16] = 0;
    const char *dev_override = getenv("DM_DEV_DIR");
    if (dev_override && *dev_override) {
        snprintf(g_dev_dir, sizeof g_dev_dir, "%s", dev_override);
    } else {
        snprintf(g_dev_dir, sizeof g_dev_dir, "/sdcard/Download/nx2a_dev/%s", g_title_lower);
    }
    struct stat st;
    if (stat(g_dev_dir, &st) != 0 || !S_ISDIR(st.st_mode)) {
        atomic_store(&g_qa_active, 0);
        return;
    }
    snprintf(g_out_dir, sizeof g_out_dir, "%s/out", g_dev_dir);
    mkdir_p(g_out_dir);
    const char *af = getenv("DM_DEV_AUTO_FLICKER");
    atomic_store(&g_auto_flicker, (af && atoi(af) > 0) ? 1 : 0);
    atomic_store(&g_qa_active, 1);
    LOG("[qa] Dev QA initialized for %s (%s)", g_title_lower, g_dev_dir);
    const char *rep = getenv("DM_DEV_REPLAY");
    if (rep && atoi(rep) > 0) qa_input_start_replay();
}

void qa_status_json(char *out, size_t cap) {
    if (!out || !cap) return;
    int active = qa_dev_enabled();
    int in_mode = qa_input_mode();
    uint32_t ridx = 0, rtot = 0, rf = 0;
    pthread_mutex_lock(&g_in_mu);
    ridx = g_replay_idx; rtot = g_replay_total; rf = g_rec_frames;
    pthread_mutex_unlock(&g_in_mu);

    char rec_path[900];
    int has_rec = 0;
    if (active) {
        struct stat st;
        snprintf(rec_path, sizeof rec_path, "%s/stress_test.rec", g_dev_dir);
        has_rec = (stat(rec_path, &st) == 0 && st.st_size > 16);
    }

    snprintf(out, cap,
             "{\"active\":%s,\"driver\":\"%s\",\"shadersLoaded\":%u,\"capturedShaders\":%d,"
             "\"autoFlicker\":%s,\"recording\":%s,\"replaying\":%s,\"hasRec\":%s,"
             "\"recFrames\":%u,\"replayIdx\":%u,\"replayTotal\":%u,"
             "\"frame\":%llu,\"lastGlitchFrame\":%lld,\"lastGlitchMs\":%llu,\"glitchesSaved\":%u}",
             active ? "true" : "false",
             g_vk_driver,
             vk_shader_bundle_count(),
             count_captured_shaders(),
             qa_get_auto_flicker() ? "true" : "false",
             in_mode == QA_INPUT_RECORD ? "true" : "false",
             in_mode == QA_INPUT_REPLAY ? "true" : "false",
             has_rec ? "true" : "false",
             rf, ridx, rtot,
             (unsigned long long)qa_current_frame(),
             (long long)atomic_load(&g_last_glitch_frame),
             (unsigned long long)atomic_load(&g_last_glitch_ms),
             atomic_load(&g_glitches_saved));
}
