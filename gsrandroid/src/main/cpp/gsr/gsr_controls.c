#include "gsr_controls.h"
#include "gsr_settings.h"
#include "gsr_host.h"
#include "gsr_text.h"
#include "descore.h"
#include "touch/descore_touch.h"
#include "gsr_touch.h"
#include <time.h>

#include <GLES3/gl3.h>
#include <android/log.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Android KeyEvent codes */
enum {
	K_DPAD_UP = 19, K_DPAD_DOWN = 20, K_DPAD_LEFT = 21, K_DPAD_RIGHT = 22,
	K_BTN_A = 96, K_BTN_B = 97, K_BTN_X = 99, K_BTN_Y = 100, K_BTN_L1 = 102, K_BTN_R1 = 103,
	K_BACK = 4, K_BTN_L2 = 104, K_BTN_R2 = 105, K_BTN_START = 108, K_BTN_SELECT = 109, K_BTN_MODE = 110, K_BTN_THUMBR = 107
};
/* Android MotionEvent axes */
enum { AX_X = 0, AX_Y = 1, AX_HAT_X = 15, AX_HAT_Y = 16, AX_LT = 17, AX_RT = 18, AX_GAS = 22, AX_BRAKE = 23 };


static int g_bind[GSR_ACT_COUNT][GSR_BIND_SLOTS];
static atomic_uint g_slot_held;   /* bit act*2+slot */
static atomic_uint g_stick_dirs;  /* left stick, as action bits */
static atomic_uint g_hat_dirs;
static atomic_uint g_touch_mask;  /* touch buttons, action bits */
static atomic_uint g_touch_dirs;  /* touch d-pad circle, action bits */
static atomic_int g_ff_latch, g_ff_prev, g_menu_prev, g_run_prev, g_run_latch;
static atomic_llong g_touch_down_at[GSR_ACT_COUNT];  /* when a touch button went down (for the minimum press) */
static atomic_llong g_tap_until[GSR_ACT_COUNT];      /* timed presses: held until this time */
static atomic_llong g_turbo_since[GSR_ACT_COUNT];    /* turbo held since (0 = not held) */
static atomic_llong g_touch_ff_down;
static atomic_int g_touch_ff_hold, g_touch_ff_latch;
static char g_path[1024];
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static int g_w, g_h;

/* ---- bindings ------------------------------------------------------------------------------------- */

static void defaults(void) {
	memset(g_bind, 0, sizeof g_bind);
	/* The south button is A and the east button is B (the way Android pads are labelled); the other two
	 * face buttons repeat them. */
	g_bind[GSR_ACT_A][0] = K_BTN_A;       g_bind[GSR_ACT_A][1] = K_BTN_X;
	g_bind[GSR_ACT_B][0] = K_BTN_B;       g_bind[GSR_ACT_B][1] = K_BTN_Y;
	g_bind[GSR_ACT_L][0] = K_BTN_L1;      g_bind[GSR_ACT_R][0] = K_BTN_R1;
	g_bind[GSR_ACT_START][0] = K_BTN_START;
	g_bind[GSR_ACT_SELECT][0] = K_BTN_SELECT;
	g_bind[GSR_ACT_UP][0] = K_DPAD_UP;    g_bind[GSR_ACT_DOWN][0] = K_DPAD_DOWN;
	g_bind[GSR_ACT_LEFT][0] = K_DPAD_LEFT; g_bind[GSR_ACT_RIGHT][0] = K_DPAD_RIGHT;
	g_bind[GSR_ACT_FF][0] = K_BTN_R2;
	g_bind[GSR_ACT_MENU][0] = K_BTN_THUMBR; g_bind[GSR_ACT_MENU][1] = K_BTN_MODE;
	g_bind[GSR_ACT_RUN][0] = 106;  /* KEYCODE_BUTTON_THUMBL: clicking the left stick toggles running */
}

static void save_binds(void) {
	char tmp[1100];
	FILE *f;
	if (!g_path[0]) return;
	snprintf(tmp, sizeof tmp, "%s.tmp", g_path);
	f = fopen(tmp, "w");
	if (!f) return;
	for (int a = 0; a < GSR_ACT_COUNT; a++) fprintf(f, "%d=%d,%d\n", a, g_bind[a][0], g_bind[a][1]);
	fclose(f);
	rename(tmp, g_path);
}

static void load_binds(void) {
	char line[64];
	FILE *f = fopen(g_path, "r");
	if (!f) return;
	while (fgets(line, sizeof line, f)) {
		int a, k0, k1;
		if (sscanf(line, "%d=%d,%d", &a, &k0, &k1) == 3 && a >= 0 && a < GSR_ACT_COUNT) {
			g_bind[a][0] = k0; g_bind[a][1] = k1;
		}
	}
	fclose(f);
}

int gsr_controls_bind_get(GsrAction a, int slot) {
	return (a >= 0 && a < GSR_ACT_COUNT && slot >= 0 && slot < GSR_BIND_SLOTS) ? g_bind[a][slot] : 0;
}

void gsr_controls_bind_set(GsrAction a, int slot, int key) {
	if (a < 0 || a >= GSR_ACT_COUNT || slot < 0 || slot >= GSR_BIND_SLOTS) return;
	pthread_mutex_lock(&g_mu);
	if (key != 0)
		for (int i = 0; i < GSR_ACT_COUNT; i++)
			for (int s = 0; s < GSR_BIND_SLOTS; s++)
				if (g_bind[i][s] == key) g_bind[i][s] = 0;
	g_bind[a][slot] = key;
	atomic_store(&g_slot_held, 0);
	save_binds();
	pthread_mutex_unlock(&g_mu);
}

void gsr_controls_bind_reset(void) {
	pthread_mutex_lock(&g_mu);
	defaults();
	atomic_store(&g_slot_held, 0);
	save_binds();
	pthread_mutex_unlock(&g_mu);
}

/* ---- state -> engine -------------------------------------------------------------------------------- */

static unsigned timed_actions(void);

static unsigned held_actions(void) {
	unsigned slots = atomic_load(&g_slot_held), m = 0;
	for (int a = 0; a < GSR_ACT_COUNT; a++)
		if (slots & (3u << (a * 2))) m |= 1u << a;
	return m | atomic_load(&g_stick_dirs) | atomic_load(&g_hat_dirs) | atomic_load(&g_touch_mask) | atomic_load(&g_touch_dirs)
		| timed_actions();
}

/* Fast forward as a toggle latches on the press edge, wherever the press came from. */
static void ff_changed(void) {
	int now = (held_actions() >> GSR_ACT_FF) & 1;
	int prev = atomic_exchange(&g_ff_prev, now);
	if (now && !prev && gsr_settings_get(GSR_S_FF_MODE) == 1) atomic_fetch_xor(&g_ff_latch, 1);
}

/* The menu action (a gamepad button, the touch MENU button, or Back) asks the Java side to open the settings. */
static void menu_changed(void) {
	int now = (held_actions() >> GSR_ACT_MENU) & 1;
	int prev = atomic_exchange(&g_menu_prev, now);
	if (now && !prev) descore_request_menu();
}

/* Run: a gamepad button bound to it toggles running on its press. */
static void run_changed(void) {
	int now = (held_actions() >> GSR_ACT_RUN) & 1;
	int prev = atomic_exchange(&g_run_prev, now);
	if (now && !prev) atomic_fetch_xor(&g_run_latch, 1);
}

void gsr_controls_touch_run_toggle(void) { atomic_fetch_xor(&g_run_latch, 1); }
int gsr_controls_run_active(void) { return atomic_load(&g_run_latch); }

static void edges(void) {
	ff_changed();
	menu_changed();
	run_changed();
}

void gsr_controls_release_all(void) {
	atomic_store(&g_slot_held, 0);
	atomic_store(&g_stick_dirs, 0);
	atomic_store(&g_hat_dirs, 0);
	atomic_store(&g_touch_mask, 0);
	atomic_store(&g_touch_dirs, 0);
	atomic_store(&g_ff_prev, 0);
	atomic_store(&g_menu_prev, 0);
	atomic_store(&g_run_prev, 0);
	for (int a = 0; a < GSR_ACT_COUNT; a++) { atomic_store(&g_tap_until[a], 0); atomic_store(&g_turbo_since[a], 0); }
	atomic_store(&g_touch_ff_hold, 0);
	gsr_touch_release_all();
}

uint16_t gsr_controls_keyinput(void) {
	unsigned m = held_actions();
	if ((m & (1u << GSR_ACT_LEFT)) && (m & (1u << GSR_ACT_RIGHT))) m &= ~((1u << GSR_ACT_LEFT) | (1u << GSR_ACT_RIGHT));
	if ((m & (1u << GSR_ACT_UP)) && (m & (1u << GSR_ACT_DOWN))) m &= ~((1u << GSR_ACT_UP) | (1u << GSR_ACT_DOWN));
	return (uint16_t) (~m & 0x3FFu);
}

int gsr_controls_fast_forward(void) {
	/* the touch FF button: a tap toggles, holding it fast-forwards while held (whatever the gamepad setting is) */
	if (atomic_load(&g_touch_ff_hold) || atomic_load(&g_touch_ff_latch)) return 1;
	if (gsr_settings_get(GSR_S_FF_MODE) == 1) return atomic_load(&g_ff_latch);
	atomic_store(&g_ff_latch, 0);
	return (held_actions() >> GSR_ACT_FF) & 1;
}

/* ---- gamepad ---------------------------------------------------------------------------------------- */

static void key_internal(int code, int down) {
	if (code == K_BACK) {
		if (down) descore_request_menu();
		return;
	}
	int matched = 0;
	for (int a = 0; a < GSR_ACT_COUNT; a++)
		for (int s = 0; s < GSR_BIND_SLOTS; s++)
			if (g_bind[a][s] == code) {
				unsigned bit = 1u << (a * 2 + s);
				matched = 1;
				if (down) atomic_fetch_or(&g_slot_held, bit); else atomic_fetch_and(&g_slot_held, ~bit);
			}
	if (down && !matched) __android_log_print(ANDROID_LOG_INFO, "gsr-input", "key %d is not bound to anything", code);
	edges();
}

void gsr_controls_key(int code, int down) { key_internal(code, down); }

static void axis_trigger(int code, float v, atomic_int *state) {
	int now = v > 0.5f ? 1 : (v < 0.3f ? 0 : atomic_load(state));
	if (now != atomic_exchange(state, now)) key_internal(code, now);
}

void gsr_controls_axis(int axis, float v) {
	static atomic_int lt, rt;
	unsigned d;
	switch (axis) {
	case AX_X: case AX_Y: {
		static float sx, sy;
		if (axis == AX_X) sx = v; else sy = v;
		d = 0;
		if (sx < -0.5f) d |= 1u << GSR_ACT_LEFT;
		if (sx > 0.5f) d |= 1u << GSR_ACT_RIGHT;
		if (sy < -0.5f) d |= 1u << GSR_ACT_UP;
		if (sy > 0.5f) d |= 1u << GSR_ACT_DOWN;
		atomic_store(&g_stick_dirs, d);
		break;
	}
	case AX_HAT_X: case AX_HAT_Y: {
		static float hx, hy;
		if (axis == AX_HAT_X) hx = v; else hy = v;
		d = 0;
		if (hx < -0.5f) d |= 1u << GSR_ACT_LEFT;
		if (hx > 0.5f) d |= 1u << GSR_ACT_RIGHT;
		if (hy < -0.5f) d |= 1u << GSR_ACT_UP;
		if (hy > 0.5f) d |= 1u << GSR_ACT_DOWN;
		atomic_store(&g_hat_dirs, d);
		break;
	}
	case AX_LT: case AX_BRAKE: axis_trigger(K_BTN_L2, v, &lt); break;
	case AX_RT: case AX_GAS: axis_trigger(K_BTN_R2, v, &rt); break;
	default: return;
	}
	edges();
}

/* ---- touch overlay ----------------------------------------------------------------------------------
 * gsr_touch.c handles the fingers and draws the controls; this file says where each control goes by default and
 * owns the resulting button state. */

enum { TB_A, TB_B, TB_L, TB_R, TB_START, TB_SELECT, TB_FF, TB_MENU, TB_COUNT };
static const int k_tb_elem[TB_COUNT] = { GT_A, GT_B, GT_L, GT_R, GT_START, GT_SELECT, GT_FF, GT_MENU };
static float g_tb_rect[TB_COUNT][4];
static float g_pad[3];  /* the direction pad: centre x, y and radius */

#define MIN_PRESS_MS 80   /* the game reads input once a frame: a quick tap must last long enough to be seen */
#define FF_TAP_MS 300     /* the touch FF button: a tap shorter than this toggles, a longer press holds */

static long long ms_now(void) {
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (long long) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}


/* Timed presses (taps, the rest of a too-short press) and turbo pulses, as action bits. */
static unsigned timed_actions(void) {
	long long now = ms_now();
	unsigned m = 0;
	int rate = gsr_settings_get(GSR_S_TURBO_RATE);
	for (int a = 0; a < GSR_ACT_COUNT; a++) {
		long long since;
		if (atomic_load(&g_tap_until[a]) > now) m |= 1u << a;
		since = atomic_load(&g_turbo_since[a]);
		/* on for the first half of each 1/rate second, off for the second */
		if (since && ((now - since) * rate * 2 / 1000) % 2 == 0) m |= 1u << a;
	}
	return m;
}

void gsr_controls_touch_set(int act, int down) {
	if (act < 0 || act >= GSR_ACT_COUNT) return;
	if (down) {
		atomic_store(&g_touch_down_at[act], ms_now());
		atomic_fetch_or(&g_touch_mask, 1u << act);
	} else {
		long long held = ms_now() - atomic_load(&g_touch_down_at[act]);
		if (held < MIN_PRESS_MS) atomic_store(&g_tap_until[act], atomic_load(&g_touch_down_at[act]) + MIN_PRESS_MS);
		atomic_fetch_and(&g_touch_mask, ~(1u << act));
	}
	edges();
}

void gsr_controls_touch_tap(int act) {
	if (act < 0 || act >= GSR_ACT_COUNT) return;
	atomic_store(&g_tap_until[act], ms_now() + MIN_PRESS_MS);
}

void gsr_controls_touch_dirs(unsigned action_bits) {
	atomic_store(&g_touch_dirs, action_bits);
}

void gsr_controls_touch_turbo(int act, int down) {
	if (act < 0 || act >= GSR_ACT_COUNT) return;
	atomic_store(&g_turbo_since[act], down ? ms_now() : 0);
}

void gsr_controls_touch_ff(int down) {
	if (down) {
		atomic_store(&g_touch_ff_down, ms_now());
		atomic_store(&g_touch_ff_hold, 1);
	} else {
		if (ms_now() - atomic_load(&g_touch_ff_down) < FF_TAP_MS) atomic_fetch_xor(&g_touch_ff_latch, 1);
		atomic_store(&g_touch_ff_hold, 0);
	}
}

/* Portrait: the same controls in the space below the picture, laid out like the handheld itself: L and R
 * (with FF and MENU between them) along the top of that space, the stick or d-pad on the left and A/B on the
 * right in the middle, SELECT and START at the bottom. */
static void portrait_layout(int w, int h, float cell, float m, float r, float sb) {
	float y0 = (float) (gsr_host_portrait_top(w, h) + gsr_host_portrait_picture_h(w, h));
	float *a = g_tb_rect[TB_A], *b = g_tb_rect[TB_B], *l = g_tb_rect[TB_L], *rr = g_tb_rect[TB_R];
	float *st = g_tb_rect[TB_START], *se = g_tb_rect[TB_SELECT], *ff = g_tb_rect[TB_FF], *mn = g_tb_rect[TB_MENU];
	float top = y0 + m, bottom = h - m - cell * 0.8f;
	float mid = (top + cell * 0.95f + bottom) * 0.5f;  /* between the shoulder row and SELECT/START */
	if (gsr_settings_get(GSR_S_PORTRAIT_COMPACT) == 1) {
		/* Compact, under the picture: the rows packed right below it, the rest of the screen left free */
		mid = top + cell * 0.95f + m + r;
		bottom = mid + r + m;
		if (bottom + cell * 0.8f > h - m) bottom = h - m - cell * 0.8f;
	} else if (gsr_settings_get(GSR_S_PORTRAIT_COMPACT) == 2) {
		/* Compact, at the bottom: the same block against the bottom edge (comfortable to hold), the middle free */
		mid = bottom - m - r;
		top = mid - r - m - cell * 0.95f;
		if (top < y0 + m) top = y0 + m;
	}
	float rx = w - m - sb * 2.4f, ry = mid - sb * 0.9f;
	g_pad[0] = m + r * 1.05f; g_pad[1] = mid; g_pad[2] = r;
	a[0] = rx + sb * 1.15f; a[1] = ry; a[2] = sb; a[3] = sb;
	b[0] = rx; b[1] = ry + sb * 0.85f; b[2] = sb; b[3] = sb;
	l[0] = m; l[1] = top; l[2] = cell * 1.8f; l[3] = cell * 0.95f;
	rr[0] = w - m - cell * 1.8f; rr[1] = top; rr[2] = cell * 1.8f; rr[3] = cell * 0.95f;
	/* narrower than in landscape, so they clear L and R on a narrow screen */
	ff[0] = w * 0.5f - cell * 1.25f; ff[1] = top; ff[2] = cell * 1.2f; ff[3] = cell * 0.8f;
	mn[0] = w * 0.5f + cell * 0.05f; mn[1] = top; mn[2] = cell * 1.2f; mn[3] = cell * 0.8f;
	se[0] = w * 0.5f - cell * 1.9f; se[1] = bottom; se[2] = cell * 1.8f; se[3] = cell * 0.8f;
	st[0] = w * 0.5f + cell * 0.1f; st[1] = bottom; st[2] = cell * 1.8f; st[3] = cell * 0.8f;
}

void gsr_controls_layout(int w, int h) {
	float cell, m, r, sb;
	float scale = descore_touch_scale_value(gsr_settings_get(GSR_S_TOUCH_SCALE));
	g_w = w; g_h = h;
	descore_touch_set_screen_size(w, h);
	/* A thirteenth of the screen's short side, but no bigger than 52 dp before the size setting: on a tablet-sized
	 * screen (a foldable's inner screen) a fraction of the screen made thumb-sized buttons two to three times too
	 * big. dp = descore_touch_min_cell_px() / 44 (the 44 dp floor). */
	const float dp = descore_touch_min_cell_px() / 44.0f;
	cell = (w < h ? w : h) * 0.13f;
	if (cell > 52.0f * dp) cell = 52.0f * dp;
	cell *= scale;
	if (gsr_host_portrait_picture_h(w, h) > 0 && gsr_settings_get(GSR_S_PORTRAIT_COMPACT)) {
		/* Compact portrait: smaller buttons (down to 36 dp instead of 44) */
		cell = w * 0.105f;
		if (cell > 42.0f * dp) cell = 42.0f * dp;
		cell *= scale;
		if (cell < descore_touch_min_cell_px() * (36.0f / 44.0f)) cell = descore_touch_min_cell_px() * (36.0f / 44.0f);
	} else if (cell < descore_touch_min_cell_px()) cell = descore_touch_min_cell_px();
	m = cell * 0.35f;
	r = cell * 1.45f;
	sb = cell * 1.25f;
	if (gsr_host_portrait_picture_h(w, h) > 0) {
		portrait_layout(w, h, cell, m, r, sb);
	} else {
	g_pad[0] = m + r * 1.05f; g_pad[1] = h - m - r * 1.05f; g_pad[2] = r;
	{
		float rx = w - m - sb * 2.4f, ry = h - m - sb * 1.9f;
		float *a = g_tb_rect[TB_A], *b = g_tb_rect[TB_B], *l = g_tb_rect[TB_L], *rr = g_tb_rect[TB_R];
		float *st = g_tb_rect[TB_START], *se = g_tb_rect[TB_SELECT], *ff = g_tb_rect[TB_FF], *mn = g_tb_rect[TB_MENU];
		a[0] = rx + sb * 1.15f; a[1] = ry; a[2] = sb; a[3] = sb;
		b[0] = rx; b[1] = ry + sb * 0.85f; b[2] = sb; b[3] = sb;
		l[0] = m; l[1] = m; l[2] = cell * 1.8f; l[3] = cell * 0.95f;
		rr[0] = w - m - cell * 1.8f; rr[1] = m; rr[2] = cell * 1.8f; rr[3] = cell * 0.95f;
		se[0] = w * 0.5f - cell * 1.9f; se[1] = h - m - cell * 0.8f; se[2] = cell * 1.8f; se[3] = cell * 0.8f;
		st[0] = w * 0.5f + cell * 0.1f; st[1] = se[1]; st[2] = cell * 1.8f; st[3] = cell * 0.8f;
		ff[0] = w * 0.5f - cell * 1.9f; ff[1] = m; ff[2] = cell * 1.8f; ff[3] = cell * 0.8f;
		mn[0] = w * 0.5f + cell * 0.1f; mn[1] = m; mn[2] = cell * 1.8f; mn[3] = cell * 0.8f;
	}
	}
	for (int i = 0; i < TB_COUNT; i++)
		gsr_touch_set_default(k_tb_elem[i], g_tb_rect[i][0], g_tb_rect[i][1], g_tb_rect[i][2], g_tb_rect[i][3]);
	gsr_touch_set_default(GT_DPAD, g_pad[0] - g_pad[2], g_pad[1] - g_pad[2], g_pad[2] * 2, g_pad[2] * 2);
	/* Turbo A and B (when turned on): smaller round buttons just outside A and B */
	{
		const float *a = g_tb_rect[TB_A], *b = g_tb_rect[TB_B], t = sb * 0.7f;
		gsr_touch_set_default(GT_TURBO_A, a[0] + (a[2] - t) * 0.5f, a[1] - t - m * 0.5f, t, t);
		gsr_touch_set_default(GT_TURBO_B, b[0] - t - m * 0.5f, b[1] + (b[3] - t) * 0.5f, t, t);
	}
	/* Run (when turned on): a small pill just above the direction pad */
	gsr_touch_set_default(GT_RUN, g_pad[0] - cell * 0.75f, g_pad[1] - g_pad[2] - cell * 0.8f - m, cell * 1.5f, cell * 0.8f);
	gsr_touch_layout_done(w, h);
}

void gsr_controls_touch_settings_changed(void) {
	descore_touch_set_scale_step(gsr_settings_get(GSR_S_TOUCH_SCALE));
	if (g_w > 0) gsr_controls_layout(g_w, g_h);
}

void gsr_controls_init(const char *dir) {
	snprintf(g_path, sizeof g_path, "%s/controls.ini", dir);
	defaults();
	load_binds();
	/* descore's touch module still tracks the gamepad; gsr_touch.c takes over the fingers and the drawing. */
	descore_touch_init(0, NULL, NULL);
	descore_touch_set_override(gsr_touch_event, gsr_touch_draw);
	descore_touch_set_scale_step(gsr_settings_get(GSR_S_TOUCH_SCALE));
	gsr_touch_init(dir);
}

/* The captions are drawn with the controls by gsr_touch.c (descore's touch draw at the buffer swap). */
void gsr_controls_draw_labels(int sw, int sh) { (void) sw; (void) sh; }
