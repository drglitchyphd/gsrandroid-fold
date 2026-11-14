/* gsr_touch.h -- Golden Sun's on-screen touch controls: input, drawing, the floating D-pad, tap gestures,
 * fading and the layout editor.
 *
 * gsr_controls.c works out the default place of every control for the screen (gsr_touch_set_default) and owns the
 * game's button state; this module takes the raw touch events (through descore's touch override), decides what
 * each finger is doing and reports it back through gsr_controls_touch_*(). Drawing is one shader that draws a
 * rounded box, a circle or a cross with an optional outline, so the three button styles need no textures. The
 * labels use the same pixel font as the FPS counter.
 *
 * Threads: touch events arrive on the UI thread, drawing happens on the render thread and the editor calls come
 * from the UI thread through JNI; everything here takes one mutex. */
#ifndef GSR_TOUCH_H
#define GSR_TOUCH_H
#ifdef __cplusplus
extern "C" {
#endif

enum { GT_DPAD, GT_A, GT_B, GT_L, GT_R, GT_START, GT_SELECT, GT_FF, GT_MENU, GT_TURBO_A, GT_TURBO_B, GT_COUNT };

/* Where layouts are saved (the shared settings folder). Call once, before the first layout. */
void gsr_touch_init(const char *dir);
/* The screen profile (GsrProfile: inner_landscape, outer_portrait, ...): each has its own saved layout. */
void gsr_touch_set_profile(const char *profile);
/* The wallpaper colour scheme's button fill and label colours (0xRRGGBB), for the "Wallpaper" button colour. */
void gsr_touch_set_palette(unsigned fill, unsigned on_fill);

/* Layout: gsr_controls_layout gives every control's default rectangle in pixels (for GT_DPAD the square around the
 * pad), then calls gsr_touch_layout_done, which applies the player's own layout for this profile. */
void gsr_touch_set_default(int elem, float x, float y, float w, float h);
void gsr_touch_layout_done(int screen_w, int screen_h);

/* A raw pointer event (Android's masked action, pointer id, position in pixels). True when consumed. */
int gsr_touch_event(int action, int pointer_id, float x, float y);
/* Draws the controls over the frame (called by descore right before the buffers are swapped). */
void gsr_touch_draw(void);
/* Forget every finger (focus lost, the menu opened, a gamepad took over). */
void gsr_touch_release_all(void);

/* ---- Layout editor (Settings > Controls > Edit touch layout). The game is paused while it is open. ---- */
void gsr_touch_edit_begin(void);
void gsr_touch_edit_end(int save);       /* save != 0 writes the layout, otherwise it is put back as it was */
void gsr_touch_edit_reset(void);         /* every control back to its default place (saved with Save) */
int gsr_touch_editing(void);
int gsr_touch_edit_selected(void);       /* GT_* or -1 */
const char *gsr_touch_label(int elem);   /* "D-pad", "A", "Turbo A", ... */
float gsr_touch_edit_size(void);         /* the selected control's size, 1.0 = default */
void gsr_touch_edit_set_size(float scale);
int gsr_touch_edit_shown(void);
void gsr_touch_edit_set_shown(int shown);
/* Delete this profile's saved layout (Settings > Controls > Reset touch layout). */
void gsr_touch_reset_layout(void);

#ifdef __cplusplus
}
#endif
#endif
