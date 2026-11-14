/* gsr_touch.c -- see gsr_touch.h. */
#define _GNU_SOURCE 1
#include "gsr_touch.h"
#include "gsr_controls.h"
#include "gsr_host.h"
#include "gsr_settings.h"
#include "gsr_text.h"
#include "descore.h"
#include "touch/descore_touch.h"

#include <GLES3/gl3.h>
#include <math.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* ---- tuning (from the touch-controls design notes) ------------------------------------------------------- */
#define HIT_SCALE 1.3f          /* touch area around a control, relative to what is drawn */
#define TAP_MS 300              /* a tap: shorter than this ... */
#define TAP_MOVE 0.02f          /* ... and moving less than this fraction of the screen height */
#define PAIR_MS 400             /* two fingers landing this close together are a two-finger tap */
#define PAIR_MUTE_MS 300        /* single taps ignored this long after a two-finger tap */
#define FLOAT_DEAD 0.18f        /* floating D-pad: dead zone, fraction of its radius */

static const char *k_name[GT_COUNT] = { "dpad", "a", "b", "l", "r", "start", "select", "ff", "menu", "turbo_a", "turbo_b" };
static const char *k_label[GT_COUNT] = { "D-pad", "A", "B", "L", "R", "Start", "Select", "Fast forward", "Menu", "Turbo A", "Turbo B" };
static const char *k_caption[GT_COUNT] = { "", "A", "B", "L", "R", "START", "SELECT", "FF", "MENU", "TA", "TB" };
static const int k_action[GT_COUNT] = { -1, GSR_ACT_A, GSR_ACT_B, GSR_ACT_L, GSR_ACT_R, GSR_ACT_START, GSR_ACT_SELECT,
		GSR_ACT_FF, GSR_ACT_MENU, GSR_ACT_A, GSR_ACT_B };

typedef struct {
	float dx, dy, dw, dh;   /* default rectangle (pixels) */
	float x, y, w, h;       /* in use */
	int ov;                 /* the player moved or resized it: fx, fy, fs, shown apply */
	float fx, fy, fs;       /* centre as a fraction of the screen, size relative to the default */
	int shown;
} Elem;

enum { P_NONE, P_BUTTON, P_PAD, P_FLOAT, P_TAP, P_EDIT };
typedef struct {
	int kind, elem, pair;
	float sx, sy, x, y, moved;
	long long t0;
} Ptr;

/* Recursive: a touch can open the menu (MENU button) and opening it releases every button, which comes back in here. */
#if defined(PTHREAD_RECURSIVE_MUTEX_INITIALIZER)
static pthread_mutex_t g_mu = PTHREAD_RECURSIVE_MUTEX_INITIALIZER;
#else
static pthread_mutex_t g_mu = PTHREAD_RECURSIVE_MUTEX_INITIALIZER_NP;
#endif
static Elem g_e[GT_COUNT], g_saved[GT_COUNT];
static Ptr g_p[DESCORE_TOUCH_MAX_POINTERS];
static int g_w, g_h;
static char g_dir[1024], g_profile[64] = "outer_landscape";
static int g_loaded_for_profile;   /* the saved layout for g_profile has been read */
static unsigned g_pal_fill = 0x3D5A80, g_pal_on = 0xFFFFFF;
static long long g_last_touch_ms, g_mute_until;
static int g_editing, g_sel = -1;
static float g_drag_dx, g_drag_dy;
static float g_pad_vx, g_pad_vy;          /* the pad's current tilt, -1..1, for drawing */
static float g_float_cx, g_float_cy;      /* floating D-pad centre while a finger holds it */
static int g_float_on;
static int g_pad_dirs;                     /* DESCORE_DPAD_* bits being pressed */

static long long now_ms(void) {
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (long long) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* ---- which controls are in use ---------------------------------------------------------------------------- */

static int scheme(void) { return gsr_settings_get(GSR_S_TOUCH_DPAD); }  /* 0 joystick, 1 D-pad, 2 floating D-pad */

/* Offered at all (by the settings), whether or not the player hid it in the editor. */
static int offered(int i) {
	switch (i) {
	case GT_DPAD: return scheme() != 2;
	case GT_A: return gsr_settings_get(GSR_S_SHOW_A);
	case GT_B: return gsr_settings_get(GSR_S_SHOW_B);
	case GT_TURBO_A: case GT_TURBO_B: return gsr_settings_get(GSR_S_TURBO);
	default: return 1;
	}
}

static int visible(int i) { return offered(i) && (!g_e[i].ov || g_e[i].shown); }

static void apply_elem(int i) {
	Elem *e = &g_e[i];
	if (e->ov && g_w > 0) {
		e->w = e->dw * e->fs; e->h = e->dh * e->fs;
		e->x = e->fx * g_w - e->w * 0.5f; e->y = e->fy * g_h - e->h * 0.5f;
	} else {
		e->x = e->dx; e->y = e->dy; e->w = e->dw; e->h = e->dh;
	}
}

/* ---- saved layouts ---------------------------------------------------------------------------------------- */

static void layout_path(char *out, size_t n) { snprintf(out, n, "%s/touch_layout_%s.txt", g_dir, g_profile); }

static void load_layout(void) {
	char path[1200], line[160];
	FILE *f;
	for (int i = 0; i < GT_COUNT; i++) g_e[i].ov = 0;
	g_loaded_for_profile = 1;
	if (!g_dir[0]) return;
	layout_path(path, sizeof path);
	f = fopen(path, "r");
	if (!f) return;
	while (fgets(line, sizeof line, f)) {
		char name[32];
		float fx, fy, fs;
		int shown;
		if (sscanf(line, "%31s %f %f %f %d", name, &fx, &fy, &fs, &shown) != 5) continue;
		for (int i = 0; i < GT_COUNT; i++) {
			if (strcmp(name, k_name[i]) != 0) continue;
			g_e[i].ov = 1;
			g_e[i].fx = fx < 0 ? 0 : fx > 1 ? 1 : fx;
			g_e[i].fy = fy < 0 ? 0 : fy > 1 ? 1 : fy;
			g_e[i].fs = fs < 0.4f ? 0.4f : fs > 3.0f ? 3.0f : fs;
			g_e[i].shown = shown != 0;
		}
	}
	fclose(f);
}

static void save_layout(void) {
	char path[1200], tmp[1210];
	FILE *f;
	if (!g_dir[0]) return;
	layout_path(path, sizeof path);
	snprintf(tmp, sizeof tmp, "%s.tmp", path);
	f = fopen(tmp, "w");
	if (!f) return;
	fprintf(f, "# name centre_x centre_y size shown (centre as a fraction of the screen, size relative to the default)\n");
	for (int i = 0; i < GT_COUNT; i++)
		if (g_e[i].ov) fprintf(f, "%s %.4f %.4f %.3f %d\n", k_name[i], g_e[i].fx, g_e[i].fy, g_e[i].fs, g_e[i].shown);
	fclose(f);
	rename(tmp, path);
}

void gsr_touch_init(const char *dir) {
	pthread_mutex_lock(&g_mu);
	snprintf(g_dir, sizeof g_dir, "%s", dir ? dir : "");
	for (int i = 0; i < DESCORE_TOUCH_MAX_POINTERS; i++) g_p[i].kind = P_NONE;
	g_loaded_for_profile = 0;
	pthread_mutex_unlock(&g_mu);
}

void gsr_touch_set_profile(const char *profile) {
	pthread_mutex_lock(&g_mu);
	if (profile && strcmp(profile, g_profile) != 0) {
		snprintf(g_profile, sizeof g_profile, "%s", profile);
		g_loaded_for_profile = 0;
		/* this screen's own layout, at once (the next re-layout for a new screen size applies it again) */
		if (g_dir[0] && !g_editing) {
			load_layout();
			for (int i = 0; i < GT_COUNT; i++) apply_elem(i);
		}
	}
	pthread_mutex_unlock(&g_mu);
}

void gsr_touch_set_palette(unsigned fill, unsigned on_fill) {
	pthread_mutex_lock(&g_mu);
	g_pal_fill = fill & 0xFFFFFF;
	g_pal_on = on_fill & 0xFFFFFF;
	pthread_mutex_unlock(&g_mu);
}

void gsr_touch_set_default(int i, float x, float y, float w, float h) {
	if (i < 0 || i >= GT_COUNT) return;
	pthread_mutex_lock(&g_mu);
	g_e[i].dx = x; g_e[i].dy = y; g_e[i].dw = w; g_e[i].dh = h;
	pthread_mutex_unlock(&g_mu);
}

void gsr_touch_layout_done(int w, int h) {
	pthread_mutex_lock(&g_mu);
	g_w = w; g_h = h;
	if (!g_last_touch_ms) g_last_touch_ms = now_ms();  /* "idle" counts from the start, not from the epoch */
	if (!g_loaded_for_profile && !g_editing) load_layout();
	for (int i = 0; i < GT_COUNT; i++) apply_elem(i);
	pthread_mutex_unlock(&g_mu);
}

/* ---- input ------------------------------------------------------------------------------------------------ */

/* The control under (x, y): touch areas are HIT_SCALE times the drawn size, and where two overlap the one whose
 * centre is nearer (relative to its size) wins. `all` includes hidden controls (the editor). */
static int hit(float x, float y, int all) {
	int best = -1;
	float best_d = 1e9f;
	for (int i = 0; i < GT_COUNT; i++) {
		const Elem *e = &g_e[i];
		float cx, cy, hw, hh, nx, ny, d;
		if (all ? !offered(i) : !visible(i)) continue;
		if (!all && i == GT_DPAD) continue;  /* the fixed pad is a stick, handled separately */
		cx = e->x + e->w * 0.5f; cy = e->y + e->h * 0.5f;
		hw = e->w * 0.5f * HIT_SCALE; hh = e->h * 0.5f * HIT_SCALE;
		if (hw <= 0 || hh <= 0) continue;
		nx = (x - cx) / hw; ny = (y - cy) / hh;
		if (fabsf(nx) > 1.0f || fabsf(ny) > 1.0f) continue;
		d = nx * nx + ny * ny;
		if (d < best_d) { best_d = d; best = i; }
	}
	return best;
}

static int in_fixed_pad(float x, float y) {
	const Elem *e = &g_e[GT_DPAD];
	float r = e->w * 0.5f, cx = e->x + r, cy = e->y + e->h * 0.5f, dx = x - cx, dy = y - cy;
	return visible(GT_DPAD) && dx * dx + dy * dy <= r * r * HIT_SCALE * HIT_SCALE;
}

/* The floating D-pad's area: the left half of the screen (in portrait, below the picture). */
static int in_float_zone(float x, float y) {
	if (scheme() != 2 || x >= g_w * 0.5f) return 0;
	if (g_h > g_w) {
		return y >= gsr_host_portrait_top(g_w, g_h) + gsr_host_portrait_picture_h(g_w, g_h);
	}
	return 1;
}

static void press_elem(int i, int down) {
	switch (i) {
	case GT_FF: gsr_controls_touch_ff(down); break;
	case GT_TURBO_A: case GT_TURBO_B: gsr_controls_touch_turbo(k_action[i], down); break;
	default: if (k_action[i] >= 0) gsr_controls_touch_set(k_action[i], down); break;
	}
}

static unsigned dirs_to_actions(unsigned d) {
	unsigned m = 0;
	if (d & DESCORE_DPAD_LEFT) m |= 1u << GSR_ACT_LEFT;
	if (d & DESCORE_DPAD_RIGHT) m |= 1u << GSR_ACT_RIGHT;
	if (d & DESCORE_DPAD_UP) m |= 1u << GSR_ACT_UP;
	if (d & DESCORE_DPAD_DOWN) m |= 1u << GSR_ACT_DOWN;
	return m;
}

/* Joystick: each axis past 35%. D-pad (fixed or floating): 8 directions in 45-degree sectors past the dead zone. */
static void pad_update(float vx, float vy, int floating) {
	unsigned d = 0;
	float len = sqrtf(vx * vx + vy * vy);
	if (len > 1.0f) { vx /= len; vy /= len; }
	g_pad_vx = vx; g_pad_vy = vy;
	if (scheme() == 0 && !floating) {
		if (vx < -0.35f) d |= DESCORE_DPAD_LEFT;
		if (vx > 0.35f) d |= DESCORE_DPAD_RIGHT;
		if (vy < -0.35f) d |= DESCORE_DPAD_UP;
		if (vy > 0.35f) d |= DESCORE_DPAD_DOWN;
	} else if (len >= (floating ? FLOAT_DEAD : 0.2f)) {
		const float ax = fabsf(vx), ay = fabsf(vy);
		if (ax > ay * 0.414f) d |= vx < 0 ? DESCORE_DPAD_LEFT : DESCORE_DPAD_RIGHT;
		if (ay > ax * 0.414f) d |= vy < 0 ? DESCORE_DPAD_UP : DESCORE_DPAD_DOWN;
	}
	g_pad_dirs = (int) d;
	gsr_controls_touch_dirs(dirs_to_actions(d));
}

static void pad_release(void) {
	g_pad_vx = g_pad_vy = 0;
	g_pad_dirs = 0;
	g_float_on = 0;
	gsr_controls_touch_dirs(0);
}

static void release_ptr(Ptr *p) {
	if (p->kind == P_BUTTON) press_elem(p->elem, 0);
	else if (p->kind == P_PAD || p->kind == P_FLOAT) pad_release();
	p->kind = P_NONE;
}

static void release_all_locked(void) {
	for (int i = 0; i < DESCORE_TOUCH_MAX_POINTERS; i++) release_ptr(&g_p[i]);
}

void gsr_touch_release_all(void) {
	pthread_mutex_lock(&g_mu);
	release_all_locked();
	pthread_mutex_unlock(&g_mu);
}

static void edit_event(int action, int id, float x, float y) {
	Ptr *p = &g_p[id];
	if (action == DESCORE_TOUCH_ACTION_DOWN || action == DESCORE_TOUCH_ACTION_POINTER_DOWN) {
		int i = hit(x, y, 1);
		if (i < 0) { p->kind = P_NONE; return; }
		g_sel = i;
		p->kind = P_EDIT; p->elem = i;
		g_drag_dx = x - (g_e[i].x + g_e[i].w * 0.5f);
		g_drag_dy = y - (g_e[i].y + g_e[i].h * 0.5f);
	} else if (action == DESCORE_TOUCH_ACTION_MOVE) {
		Elem *e;
		float cx, cy;
		if (p->kind != P_EDIT || g_w <= 0) return;
		e = &g_e[p->elem];
		if (!e->ov) { e->ov = 1; e->fs = 1.0f; e->shown = 1; }
		cx = x - g_drag_dx; cy = y - g_drag_dy;
		/* keep the whole control on the screen */
		if (cx < e->w * 0.5f) cx = e->w * 0.5f;
		if (cx > g_w - e->w * 0.5f) cx = g_w - e->w * 0.5f;
		if (cy < e->h * 0.5f) cy = e->h * 0.5f;
		if (cy > g_h - e->h * 0.5f) cy = g_h - e->h * 0.5f;
		e->fx = cx / g_w; e->fy = cy / g_h;
		apply_elem(p->elem);
	} else {
		p->kind = P_NONE;
	}
}

int gsr_touch_event(int action, int id, float x, float y) {
	long long t = now_ms();
	int consumed = 1;
	if (id < 0 || id >= DESCORE_TOUCH_MAX_POINTERS) return 0;
	pthread_mutex_lock(&g_mu);
	if (g_editing) {
		edit_event(action, id, x, y);
		pthread_mutex_unlock(&g_mu);
		return 1;
	}
	if (descore_gamepad_connected()) {
		release_all_locked();
		pthread_mutex_unlock(&g_mu);
		return 0;
	}
	Ptr *p = &g_p[id];
	switch (action) {
	case DESCORE_TOUCH_ACTION_DOWN:
	case DESCORE_TOUCH_ACTION_POINTER_DOWN: {
		int i;
		g_last_touch_ms = t;
		if (action == DESCORE_TOUCH_ACTION_DOWN) release_all_locked();  /* a fresh gesture: nothing can still be down */
		else release_ptr(p);
		p->sx = p->x = x; p->sy = p->y = y; p->t0 = t; p->moved = 0; p->pair = -1;
		i = hit(x, y, 0);
		if (i >= 0) {
			p->kind = P_BUTTON; p->elem = i;
			press_elem(i, 1);
		} else if (in_fixed_pad(x, y)) {
			const Elem *e = &g_e[GT_DPAD];
			p->kind = P_PAD;
			pad_update((x - (e->x + e->w * 0.5f)) / (e->w * 0.5f), (y - (e->y + e->h * 0.5f)) / (e->h * 0.5f), 0);
		} else if (in_float_zone(x, y) && !g_float_on) {
			p->kind = P_FLOAT;
			g_float_cx = x; g_float_cy = y; g_float_on = 1;
			pad_update(0, 0, 1);
		} else if (gsr_settings_get(GSR_S_TAP_A) || gsr_settings_get(GSR_S_TWO_FINGER_B)) {
			p->kind = P_TAP;
			/* a second finger landing soon after another tap finger: a two-finger tap */
			if (gsr_settings_get(GSR_S_TWO_FINGER_B)) {
				for (int k = 0; k < DESCORE_TOUCH_MAX_POINTERS; k++) {
					if (k == id || g_p[k].kind != P_TAP || g_p[k].pair >= 0 || t - g_p[k].t0 > PAIR_MS) continue;
					g_p[k].pair = id; p->pair = k;
					break;
				}
			}
		} else {
			p->kind = P_NONE;
			consumed = 0;
		}
		break;
	}
	case DESCORE_TOUCH_ACTION_MOVE: {
		float dx = x - p->sx, dy = y - p->sy, d = sqrtf(dx * dx + dy * dy);
		p->x = x; p->y = y;
		if (d > p->moved) p->moved = d;
		if (p->kind == P_BUTTON && (p->elem == GT_A || p->elem == GT_B)) {
			/* slide between A and B without lifting */
			int i = hit(x, y, 0);
			if ((i == GT_A || i == GT_B) && i != p->elem) {
				press_elem(p->elem, 0);
				p->elem = i;
				press_elem(i, 1);
			}
		} else if (p->kind == P_PAD) {
			const Elem *e = &g_e[GT_DPAD];
			pad_update((x - (e->x + e->w * 0.5f)) / (e->w * 0.5f), (y - (e->y + e->h * 0.5f)) / (e->h * 0.5f), 0);
		} else if (p->kind == P_FLOAT) {
			const float r = g_e[GT_DPAD].dw * 0.5f;
			pad_update((x - g_float_cx) / r, (y - g_float_cy) / r, 1);
		} else if (p->kind == P_TAP && p->moved > TAP_MOVE * g_h) {
			p->kind = P_NONE;  /* a drag, not a tap */
		}
		break;
	}
	case DESCORE_TOUCH_ACTION_UP:
	case DESCORE_TOUCH_ACTION_POINTER_UP: {
		const int quick = t - p->t0 < TAP_MS;
		if (p->kind == P_TAP && quick) {
			if (p->pair >= 0 && g_p[p->pair].kind == P_TAP) {
				g_p[p->pair].kind = P_NONE;  /* the other finger of the pair does not tap again */
				gsr_controls_touch_tap(GSR_ACT_B);
				g_mute_until = t + PAIR_MUTE_MS;
			} else if (p->pair < 0 && gsr_settings_get(GSR_S_TAP_A) && t >= g_mute_until) {
				gsr_controls_touch_tap(GSR_ACT_A);
			}
			p->kind = P_NONE;
		} else if (p->kind == P_FLOAT && quick && p->moved < FLOAT_DEAD * g_e[GT_DPAD].dw * 0.5f
				&& gsr_settings_get(GSR_S_TAP_A) && t >= g_mute_until) {
			release_ptr(p);
			gsr_controls_touch_tap(GSR_ACT_A);  /* a tap on the D-pad side without moving: A too */
		} else {
			release_ptr(p);
		}
		break;
	}
	default:
		consumed = 0;
		break;
	}
	pthread_mutex_unlock(&g_mu);
	return consumed;
}

/* ---- editor ------------------------------------------------------------------------------------------------ */

void gsr_touch_edit_begin(void) {
	pthread_mutex_lock(&g_mu);
	release_all_locked();
	memcpy(g_saved, g_e, sizeof g_e);
	g_editing = 1;
	g_sel = -1;
	pthread_mutex_unlock(&g_mu);
}

void gsr_touch_edit_end(int save) {
	pthread_mutex_lock(&g_mu);
	if (g_editing) {
		if (save) save_layout();
		else memcpy(g_e, g_saved, sizeof g_e);
		for (int i = 0; i < GT_COUNT; i++) apply_elem(i);
	}
	for (int i = 0; i < DESCORE_TOUCH_MAX_POINTERS; i++) g_p[i].kind = P_NONE;
	g_editing = 0;
	g_sel = -1;
	pthread_mutex_unlock(&g_mu);
}

void gsr_touch_edit_reset(void) {
	pthread_mutex_lock(&g_mu);
	for (int i = 0; i < GT_COUNT; i++) { g_e[i].ov = 0; apply_elem(i); }
	pthread_mutex_unlock(&g_mu);
}

int gsr_touch_editing(void) { return g_editing; }
int gsr_touch_edit_selected(void) { return g_sel; }
const char *gsr_touch_label(int i) { return i >= 0 && i < GT_COUNT ? k_label[i] : ""; }

float gsr_touch_edit_size(void) {
	float s;
	pthread_mutex_lock(&g_mu);
	s = g_sel >= 0 && g_e[g_sel].ov ? g_e[g_sel].fs : 1.0f;
	pthread_mutex_unlock(&g_mu);
	return s;
}

static void ensure_ov(int i) {
	Elem *e = &g_e[i];
	if (e->ov || g_w <= 0) return;
	e->ov = 1; e->fs = 1.0f; e->shown = 1;
	e->fx = (e->dx + e->dw * 0.5f) / g_w;
	e->fy = (e->dy + e->dh * 0.5f) / g_h;
}

void gsr_touch_edit_set_size(float s) {
	pthread_mutex_lock(&g_mu);
	if (g_sel >= 0) {
		ensure_ov(g_sel);
		g_e[g_sel].fs = s < 0.4f ? 0.4f : s > 3.0f ? 3.0f : s;
		apply_elem(g_sel);
	}
	pthread_mutex_unlock(&g_mu);
}

int gsr_touch_edit_shown(void) {
	int v;
	pthread_mutex_lock(&g_mu);
	v = g_sel >= 0 ? (!g_e[g_sel].ov || g_e[g_sel].shown) : 1;
	pthread_mutex_unlock(&g_mu);
	return v;
}

void gsr_touch_edit_set_shown(int shown) {
	pthread_mutex_lock(&g_mu);
	if (g_sel >= 0) { ensure_ov(g_sel); g_e[g_sel].shown = shown != 0; }
	pthread_mutex_unlock(&g_mu);
}

void gsr_touch_reset_layout(void) {
	char path[1200];
	pthread_mutex_lock(&g_mu);
	if (g_dir[0]) { layout_path(path, sizeof path); remove(path); }
	for (int i = 0; i < GT_COUNT; i++) { g_e[i].ov = 0; apply_elem(i); }
	pthread_mutex_unlock(&g_mu);
}

/* ---- drawing ---------------------------------------------------------------------------------------------- */

enum { SH_BOX, SH_CROSS };
static GLuint g_prog, g_vbo, g_lprog, g_ltex;
static GLint u_screen, u_rect, u_radius, u_shape, u_fill, u_stroke, u_sw;
static GLint ul_screen, ul_color;
#define ATLAS_W 256
#define ATLAS_H 64
#define ATLAS_SCALE 3
static struct { int x, y, w, h; } g_cap[GT_COUNT];

static GLuint compile(const char *vs, const char *fs) {
	GLuint v = glCreateShader(GL_VERTEX_SHADER), f = glCreateShader(GL_FRAGMENT_SHADER), p;
	glShaderSource(v, 1, &vs, NULL); glCompileShader(v);
	glShaderSource(f, 1, &fs, NULL); glCompileShader(f);
	p = glCreateProgram();
	glAttachShader(p, v); glAttachShader(p, f);
	glLinkProgram(p);
	glDeleteShader(v); glDeleteShader(f);
	return p;
}

static void build_gl(void) {
	/* One quad per shape; the fragment shader measures the distance to a rounded box (or a cross of two) and
	 * paints the fill inside and the outline band at the edge, with a one-pixel soft edge. */
	static const char *vs = "#version 300 es\nlayout(location=0) in vec2 p; uniform vec2 screen; out vec2 px;\n"
		"void main(){ px = p; gl_Position = vec4(p.x/screen.x*2.0-1.0, 1.0-p.y/screen.y*2.0, 0.0, 1.0); }\n";
	static const char *fs = "#version 300 es\nprecision highp float; in vec2 px;\n"
		"uniform vec4 rect; uniform float radius; uniform int shape; uniform vec4 fill; uniform vec4 stroke; uniform float sw;\n"
		"out vec4 o;\n"
		"float box(vec2 p, vec2 b, float r){ r = min(r, min(b.x, b.y)); vec2 q = abs(p) - b + r;"
		" return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }\n"
		"void main(){ vec2 hb = rect.zw * 0.5; vec2 p = px - rect.xy - hb; float d;\n"
		" if (shape == 1) { float arm = min(hb.x, hb.y) * 0.34; d = min(box(p, vec2(hb.x, arm), radius), box(p, vec2(arm, hb.y), radius)); }\n"
		" else d = box(p, hb, radius);\n"
		" float inside = clamp(0.5 - d, 0.0, 1.0);\n"
		" float core = sw > 0.0 ? clamp(0.5 - (d + sw), 0.0, 1.0) : inside;\n"
		" vec4 c = mix(stroke, fill, core);\n"
		" o = vec4(c.rgb, c.a * inside); }\n";
	static const char *lvs = "#version 300 es\nlayout(location=0) in vec2 p; layout(location=1) in vec2 t; uniform vec2 screen; out vec2 uv;\n"
		"void main(){ uv = t; gl_Position = vec4(p.x/screen.x*2.0-1.0, 1.0-p.y/screen.y*2.0, 0.0, 1.0); }\n";
	static const char *lfs = "#version 300 es\nprecision mediump float; in vec2 uv; uniform sampler2D tex; uniform vec4 color; out vec4 o;\n"
		"void main(){ o = vec4(color.rgb, texture(tex, uv).r * color.a); }\n";
	static unsigned char px[ATLAS_W * ATLAS_H];
	int x = 0, y = 0;

	g_prog = compile(vs, fs);
	u_screen = glGetUniformLocation(g_prog, "screen");
	u_rect = glGetUniformLocation(g_prog, "rect");
	u_radius = glGetUniformLocation(g_prog, "radius");
	u_shape = glGetUniformLocation(g_prog, "shape");
	u_fill = glGetUniformLocation(g_prog, "fill");
	u_stroke = glGetUniformLocation(g_prog, "stroke");
	u_sw = glGetUniformLocation(g_prog, "sw");
	g_lprog = compile(lvs, lfs);
	ul_screen = glGetUniformLocation(g_lprog, "screen");
	ul_color = glGetUniformLocation(g_lprog, "color");
	glGenBuffers(1, &g_vbo);

	memset(px, 0, sizeof px);
	for (int i = 0; i < GT_COUNT; i++) {
		int w = (int) strlen(k_caption[i]) * 6 * ATLAS_SCALE, h = 8 * ATLAS_SCALE;
		if (w == 0) continue;
		if (x + w > ATLAS_W) { x = 0; y += h + 2; }
		gsr_text_blit(k_caption[i], ATLAS_SCALE, px, ATLAS_W, x, y);
		g_cap[i].x = x; g_cap[i].y = y; g_cap[i].w = w - ATLAS_SCALE; g_cap[i].h = 7 * ATLAS_SCALE;
		x += w + 2;
	}
	glGenTextures(1, &g_ltex);
	glBindTexture(GL_TEXTURE_2D, g_ltex);
	glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
	glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, ATLAS_W, ATLAS_H, 0, GL_RED, GL_UNSIGNED_BYTE, px);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

typedef struct { float r, g, b; } Rgb;
static Rgb rgb(unsigned c) { Rgb o = { ((c >> 16) & 255) / 255.0f, ((c >> 8) & 255) / 255.0f, (c & 255) / 255.0f }; return o; }
static Rgb mixc(Rgb a, Rgb b, float t) { Rgb o = { a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t }; return o; }

/* Button colour (Settings > Controls): Wallpaper, Grey, White, Dark, Blue, Red, Green, Purple. */
static void palette(Rgb *fill, Rgb *on) {
	static const unsigned k_fill[] = { 0, 0x9E9E9E, 0xF2F2F2, 0x202124, 0x3D7CB8, 0xC0392B, 0x2E7D32, 0x6A4C93 };
	static const unsigned k_on[] = { 0, 0xFFFFFF, 0x202124, 0xE8EAED, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF };
	int c = gsr_settings_get(GSR_S_TOUCH_COLOR);
	if (c <= 0 || c > 7) { *fill = rgb(g_pal_fill); *on = rgb(g_pal_on); }
	else { *fill = rgb(k_fill[c]); *on = rgb(k_on[c]); }
}

static void shape(float x, float y, float w, float h, float radius, int sh, Rgb fill, float fa, Rgb stroke, float sa, float swidth) {
	GLfloat v[8];
	const float pad = 2.0f;
	v[0] = x - pad;     v[1] = y - pad;
	v[2] = x + w + pad; v[3] = y - pad;
	v[4] = x - pad;     v[5] = y + h + pad;
	v[6] = x + w + pad; v[7] = y + h + pad;
	glUniform4f(u_rect, x, y, w, h);
	glUniform1f(u_radius, radius);
	glUniform1i(u_shape, sh);
	glUniform4f(u_fill, fill.r, fill.g, fill.b, fa);
	glUniform4f(u_stroke, stroke.r, stroke.g, stroke.b, sa);
	glUniform1f(u_sw, swidth);
	glBufferData(GL_ARRAY_BUFFER, sizeof v, v, GL_STREAM_DRAW);
	glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, (const void *) 0);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

static int round_elem(int i) { return i == GT_A || i == GT_B || i == GT_TURBO_A || i == GT_TURBO_B; }

static int is_pressed(int i) {
	if (i == GT_FF) return gsr_controls_fast_forward();
	for (int k = 0; k < DESCORE_TOUCH_MAX_POINTERS; k++)
		if (g_p[k].kind == P_BUTTON && g_p[k].elem == i) return 1;
	return 0;
}

/* The fade (Settings > Controls > Fade when idle): 1 while in use, then down to 0 over 0.6 s. */
static float fade_mul(long long t) {
	static const int k_secs[] = { 0, 3, 5, 10, 20 };
	int f = gsr_settings_get(GSR_S_TOUCH_FADE);
	long long idle, start;
	if (g_editing || f <= 0 || f > 4) return 1.0f;
	for (int k = 0; k < DESCORE_TOUCH_MAX_POINTERS; k++) if (g_p[k].kind != P_NONE) return 1.0f;
	idle = t - g_last_touch_ms;
	start = k_secs[f] * 1000LL;
	if (idle <= start) return 1.0f;
	if (idle >= start + 600) return 0.0f;
	return 1.0f - (float) (idle - start) / 600.0f;
}

static void draw_pad(float cx, float cy, float r, int style, int cross, Rgb fill, Rgb on, float a, float dp) {
	const Rgb dark = rgb(0x3A3F47);
	if (cross) {
		Rgb base = style == 2 ? dark : fill;
		const float arm = r * 0.34f;
		if (style == 1) shape(cx - r, cy - r, r * 2, r * 2, r * 0.12f, SH_CROSS, base, a * 0.12f, base, a, 2.0f * dp);
		else shape(cx - r, cy - r, r * 2, r * 2, r * 0.12f, SH_CROSS, base, a, mixc(base, rgb(0), 0.3f), a, style == 2 ? 2.0f * dp : 0.0f);
		/* the pressed arms, lit */
		Rgb lit = mixc(base, on, 0.45f);
		if (g_pad_dirs & DESCORE_DPAD_LEFT) shape(cx - r, cy - arm, r - arm, arm * 2, r * 0.12f, SH_BOX, lit, a, lit, 0, 0);
		if (g_pad_dirs & DESCORE_DPAD_RIGHT) shape(cx + arm, cy - arm, r - arm, arm * 2, r * 0.12f, SH_BOX, lit, a, lit, 0, 0);
		if (g_pad_dirs & DESCORE_DPAD_UP) shape(cx - arm, cy - r, arm * 2, r - arm, r * 0.12f, SH_BOX, lit, a, lit, 0, 0);
		if (g_pad_dirs & DESCORE_DPAD_DOWN) shape(cx - arm, cy + arm, arm * 2, r - arm, r * 0.12f, SH_BOX, lit, a, lit, 0, 0);
		return;
	}
	/* joystick: the base disc, then the thumb following the finger */
	{
		Rgb base = style == 2 ? dark : fill;
		const float tr = r * 0.4f, tx = cx + g_pad_vx * r * 0.6f, ty = cy + g_pad_vy * r * 0.6f;
		if (style == 1) shape(cx - r, cy - r, r * 2, r * 2, r, SH_BOX, base, a * 0.10f, base, a, 2.0f * dp);
		else shape(cx - r, cy - r, r * 2, r * 2, r, SH_BOX, base, a * 0.55f, base, 0, 0);
		shape(tx - tr, ty - tr, tr * 2, tr * 2, tr, SH_BOX, style == 1 ? base : mixc(base, on, 0.25f), style == 1 ? a * 0.5f : a, base, a, style == 1 ? 2.0f * dp : 0);
	}
}

static void draw_caption(int i, const Elem *e, Rgb c, float a) {
	float lw = (float) g_cap[i].w, lh = (float) g_cap[i].h, k, maxk, dw, dh, x0, y0, u0, v0, u1, v1;
	GLfloat v[16];
	if (lw <= 0) return;
	k = e->h * 0.36f / lh;
	maxk = e->w * 0.72f / lw;
	if (k > maxk) k = maxk;
	dw = lw * k; dh = lh * k;
	x0 = e->x + (e->w - dw) * 0.5f; y0 = e->y + (e->h - dh) * 0.5f;
	u0 = g_cap[i].x / (float) ATLAS_W; v0 = g_cap[i].y / (float) ATLAS_H;
	u1 = (g_cap[i].x + g_cap[i].w) / (float) ATLAS_W; v1 = (g_cap[i].y + g_cap[i].h) / (float) ATLAS_H;
	v[0] = x0;      v[1] = y0;      v[2] = u0;  v[3] = v0;
	v[4] = x0 + dw; v[5] = y0;      v[6] = u1;  v[7] = v0;
	v[8] = x0;      v[9] = y0 + dh; v[10] = u0; v[11] = v1;
	v[12] = x0 + dw; v[13] = y0 + dh; v[14] = u1; v[15] = v1;
	glUniform4f(ul_color, c.r, c.g, c.b, a);
	glBufferData(GL_ARRAY_BUFFER, sizeof v, v, GL_STREAM_DRAW);
	glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 16, (const void *) 0);
	glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 16, (const void *) 8);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

void gsr_touch_draw(void) {
	Rgb fill, on, sel = rgb(0xFFD54F);
	const Rgb dark = rgb(0x3A3F47), light = rgb(0xE8EAED);
	float base_a, fade, dp;
	int style;
	long long t = now_ms();

	pthread_mutex_lock(&g_mu);
	if (g_w <= 0 || (!g_editing && descore_gamepad_connected())) {
		if (descore_gamepad_connected()) release_all_locked();
		pthread_mutex_unlock(&g_mu);
		return;
	}
	if (!g_prog || !glIsProgram(g_prog)) build_gl();
	palette(&fill, &on);
	style = gsr_settings_get(GSR_S_TOUCH_STYLE);
	base_a = gsr_settings_get(GSR_S_TOUCH_OPACITY) / 100.0f;
	fade = fade_mul(t);
	dp = descore_touch_min_cell_px() / 44.0f;

	glBindVertexArray(0);
	glViewport(0, 0, g_w, g_h);
	glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE); glDisable(GL_SCISSOR_TEST); glDisable(GL_STENCIL_TEST);
	glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_FALSE);
	glEnable(GL_BLEND);
	glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
	glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
	glEnableVertexAttribArray(0);
	glUseProgram(g_prog);
	glUniform2f(u_screen, (float) g_w, (float) g_h);

	for (int i = 0; i < GT_COUNT; i++) {
		const Elem *e = &g_e[i];
		int shown = visible(i), pressed;
		float a = base_a * fade, r;
		Rgb f = fill;
		if (g_editing ? !offered(i) : !shown) continue;
		if (i == GT_MENU && a < base_a * 0.4f) a = base_a * 0.4f;  /* never fully faded: always findable */
		if (g_editing && !shown) a = 0.25f;
		if (g_editing && shown && a < 0.6f) a = 0.6f;
		if (a <= 0.003f) continue;
		if (i == GT_DPAD) {
			draw_pad(e->x + e->w * 0.5f, e->y + e->h * 0.5f, e->w * 0.5f, style, scheme() == 1, fill, on, a, dp);
		} else {
			pressed = is_pressed(i);
			/* GBA SP style: coloured A/B (and turbo), dark slate for everything else */
			if (style == 2 && !round_elem(i)) f = dark;
			if (pressed) f = mixc(f, style == 1 ? f : on, 0.3f);
			r = round_elem(i) ? e->w : (style == 2 ? e->h * 0.25f : e->h * 0.5f);
			if (style == 1) shape(e->x, e->y, e->w, e->h, r, SH_BOX, f, a * (pressed ? 0.35f : 0.12f), f, a, 2.0f * dp);
			else shape(e->x, e->y, e->w, e->h, r, SH_BOX, f, a, mixc(f, rgb(0), 0.3f), a, style == 2 ? 2.0f * dp : 0.0f);
		}
		if (g_editing && i == g_sel)
			shape(e->x - 4 * dp, e->y - 4 * dp, e->w + 8 * dp, e->h + 8 * dp, round_elem(i) || i == GT_DPAD ? e->w : e->h * 0.5f + 4 * dp,
					SH_BOX, sel, 0.0f, sel, 1.0f, 3.0f * dp);
	}
	/* the floating D-pad, only while a thumb holds it */
	if (!g_editing && g_float_on) {
		const float r = g_e[GT_DPAD].dw * 0.5f;
		draw_pad(g_float_cx, g_float_cy, r, style, 1, fill, on, base_a < 0.35f ? 0.35f : base_a, dp);
	}

	/* captions */
	glUseProgram(g_lprog);
	glUniform2f(ul_screen, (float) g_w, (float) g_h);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, g_ltex);
	glEnableVertexAttribArray(1);
	for (int i = 0; i < GT_COUNT; i++) {
		int shown = visible(i);
		float a = base_a * fade + 0.35f;
		Rgb c = on;
		if (i == GT_DPAD || (g_editing ? !offered(i) : !shown)) continue;
		if (a > 1.0f) a = 1.0f;
		if (!g_editing) a *= i == GT_MENU && fade < 0.4f ? 0.4f : fade;
		else if (!shown) a = 0.35f;
		if (a <= 0.003f) continue;
		if (style == 1) c = gsr_settings_get(GSR_S_TOUCH_COLOR) == 3 ? light : fill;
		else if (style == 2 && !round_elem(i)) c = light;
		draw_caption(i, &g_e[i], c, a);
	}
	glDisableVertexAttribArray(1);
	glDisableVertexAttribArray(0);
	glBindBuffer(GL_ARRAY_BUFFER, 0);
	glDisable(GL_BLEND);
	glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
	glUseProgram(0);
	pthread_mutex_unlock(&g_mu);
}
