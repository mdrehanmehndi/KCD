#include "gfx_vk.h"
#include "qa_dev.h"
#include <stdlib.h>
#include <string.h>
#include <time.h>

// 8 staging slots matching present_one's 8-fence swapchain ring (g_pfence[0..7]).
// Copies ONLY im->img (before Android UI overlays) and reads 256 samples for the
// 16x16 Auto-Flicker grid without blocking live gameplay.

enum { QA_RING = 8 };

typedef struct {
    VkBuffer buf;
    VkDeviceMemory mem;
    void *mapped;
    VkDeviceSize cap;
    uint32_t w, h;
    int bgra, flip_y;
    uint64_t frame_no;
    int pending;
    qa_grid16_t grid;
    int grid_valid;
} qa_stage_t;

static qa_stage_t g_stage[QA_RING];
static qa_grid16_t g_hist_grid[3];
static uint64_t g_hist_frame[3];
static int g_hist_slot[3] = {-1, -1, -1};
static int g_hist_n = 0;

static void ensure_stage(qa_stage_t *s, VkDeviceSize need) {
    if (s->buf && s->cap >= need) return;
    if (s->buf) { vkDestroyBuffer(V.dev, s->buf, NULL); s->buf = VK_NULL_HANDLE; }
    if (s->mem) { vkFreeMemory(V.dev, s->mem, NULL); s->mem = VK_NULL_HANDLE; s->mapped = NULL; }
    VkBufferCreateInfo bi = {
        VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, NULL, 0, need,
        VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_SHARING_MODE_EXCLUSIVE
    };
    VKCHK(vkCreateBuffer(V.dev, &bi, NULL, &s->buf));
    VkMemoryRequirements rq;
    vkGetBufferMemoryRequirements(V.dev, s->buf, &rq);
    VkMemoryAllocateInfo ai = {
        VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, NULL, rq.size,
        vk_mem_type(rq.memoryTypeBits,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT, 0)
    };
    VKCHK(vkAllocateMemory(V.dev, &ai, NULL, &s->mem));
    VKCHK(vkBindBufferMemory(V.dev, s->buf, s->mem, 0));
    VKCHK(vkMapMemory(V.dev, s->mem, 0, VK_WHOLE_SIZE, 0, &s->mapped));
    s->cap = need;
}

static uint8_t *copy_stage_bytes(const qa_stage_t *s) {
    if (!s || !s->mapped || !s->w || !s->h) return NULL;
    size_t sz = (size_t)s->w * s->h * 4;
    uint8_t *buf = malloc(sz);
    if (buf) memcpy(buf, s->mapped, sz);
    return buf;
}

static void dispatch_glitch_triplet(int slot_prev, int slot_mid, int slot_next,
                                    uint64_t target_frame, const char *reason,
                                    double layout_corr, VkFence *fences) {
    qa_raw_frame_t raw[3] = {{0}};
    int slots[3] = {slot_prev, slot_mid, slot_next};
    for (int i = 0; i < 3; i++) {
        int si = slots[i];
        if (si < 0 || si >= QA_RING) continue;
        qa_stage_t *st = &g_stage[si];
        if (!st->mapped || !st->w || !st->h) continue;
        if (st->pending && fences && vkWaitForFences(V.dev, 1, &fences[si], 1, 0) != VK_SUCCESS) {
            vkWaitForFences(V.dev, 1, &fences[si], 1, 200000000ULL);
        }
        VkMappedMemoryRange mr = {VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE, NULL, st->mem, 0, VK_WHOLE_SIZE};
        vkInvalidateMappedMemoryRanges(V.dev, 1, &mr);
        raw[i].frame_no = st->frame_no;
        raw[i].w = st->w;
        raw[i].h = st->h;
        raw[i].bgra = st->bgra;
        raw[i].flip_y = st->flip_y;
        raw[i].rgba = copy_stage_bytes(st);
    }
    qa_submit_glitch_dump(raw, target_frame, reason, layout_corr);
}

static int find_slot_for_frame(uint64_t frame_no) {
    for (int i = 0; i < QA_RING; i++) {
        if (g_stage[i].mapped && g_stage[i].frame_no == frame_no) return i;
    }
    return -1;
}

void qa_vk_record_frame_copy(VkCommandBuffer cb, uint32_t k, vkimg_t *im,
                             int cx, int cy, int cw, int ch, int flip_y, uint64_t frame_no) {
    if (!qa_dev_enabled() || !im || cw <= 0 || ch <= 0 || k >= QA_RING) return;
    if (im->fmt != VK_FORMAT_R8G8B8A8_UNORM && im->fmt != VK_FORMAT_R8G8B8A8_SRGB &&
        im->fmt != VK_FORMAT_B8G8R8A8_UNORM && im->fmt != VK_FORMAT_B8G8R8A8_SRGB) {
        return;
    }
    qa_stage_t *s = &g_stage[k];
    VkDeviceSize need = (VkDeviceSize)cw * (VkDeviceSize)ch * 4;
    ensure_stage(s, need);
    s->w = (uint32_t)cw;
    s->h = (uint32_t)ch;
    s->bgra = (im->fmt == VK_FORMAT_B8G8R8A8_UNORM || im->fmt == VK_FORMAT_B8G8R8A8_SRGB);
    s->flip_y = flip_y != 0;
    s->frame_no = frame_no;
    s->pending = 1;
    s->grid_valid = 0;
    VkBufferImageCopy c = {
        0, 0, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
        {cx, cy, 0}, {(uint32_t)cw, (uint32_t)ch, 1}
    };
    vkCmdCopyImageToBuffer(cb, im->img, VK_IMAGE_LAYOUT_GENERAL, s->buf, 1, &c);
}

void qa_vk_poll_completed(VkFence *fences) {
    if (!qa_dev_enabled() || !fences) return;
    // Process newly completed frames in ascending frame_no order
    for (;;) {
        int best = -1;
        uint64_t best_fn = UINT64_MAX;
        for (int i = 0; i < QA_RING; i++) {
            if (!g_stage[i].pending) continue;
            if (vkWaitForFences(V.dev, 1, &fences[i], 1, 0) != VK_SUCCESS) continue;
            if (g_stage[i].frame_no < best_fn) {
                best_fn = g_stage[i].frame_no;
                best = i;
            }
        }
        if (best < 0) break;
        qa_stage_t *s = &g_stage[best];
        VkMappedMemoryRange mr = {VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE, NULL, s->mem, 0, VK_WHOLE_SIZE};
        vkInvalidateMappedMemoryRanges(V.dev, 1, &mr);
        s->pending = 0;

        uint64_t pending_tgt = 0;
        char reason[64] = "";
        int have_manual = qa_pending_glitch_target(&pending_tgt, reason, sizeof reason);
        int auto_on = qa_get_auto_flicker();
        if (auto_on) {
            qa_downsample_16x16((const uint8_t *)s->mapped, s->w, s->h, s->bgra, s->flip_y, &s->grid);
            s->grid_valid = 1;
        }

        // Shift 3-frame history
        g_hist_grid[0] = g_hist_grid[1]; g_hist_frame[0] = g_hist_frame[1]; g_hist_slot[0] = g_hist_slot[1];
        g_hist_grid[1] = g_hist_grid[2]; g_hist_frame[1] = g_hist_frame[2]; g_hist_slot[1] = g_hist_slot[2];
        if (s->grid_valid) g_hist_grid[2] = s->grid;
        g_hist_frame[2] = s->frame_no;
        g_hist_slot[2] = best;
        if (g_hist_n < 3) g_hist_n++;

        if (have_manual && s->frame_no >= pending_tgt + 1) {
            int sp = find_slot_for_frame(pending_tgt > 0 ? pending_tgt - 1 : 0);
            int sm = find_slot_for_frame(pending_tgt);
            int sn = find_slot_for_frame(pending_tgt + 1);
            if (sp < 0) sp = g_hist_slot[0];
            if (sm < 0) sm = g_hist_slot[1];
            if (sn < 0) sn = best;
            qa_clear_pending_glitch(pending_tgt);
            dispatch_glitch_triplet(sp, sm, sn, pending_tgt, reason, 1.0, fences);
        } else if (auto_on && g_hist_n == 3 &&
                   g_hist_frame[1] == g_hist_frame[0] + 1 &&
                   g_hist_frame[2] == g_hist_frame[1] + 1) {
            double corr = 0.0;
            struct timespec ts;
            clock_gettime(CLOCK_MONOTONIC, &ts);
            uint64_t now_ns = (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
            if (qa_auto_flicker_should_trigger(&g_hist_grid[0], &g_hist_grid[1], &g_hist_grid[2], now_ns, &corr)) {
                dispatch_glitch_triplet(g_hist_slot[0], g_hist_slot[1], g_hist_slot[2],
                                        g_hist_frame[1], "auto_flicker", corr, fences);
            }
        }
    }
}
