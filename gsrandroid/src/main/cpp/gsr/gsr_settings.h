/* gsr_settings.h -- the player's video / audio / speed / touch options, kept as a small table of integers
 * that is loaded from and saved to <files>/gsr/settings.ini. Thread safe: the UI thread writes, the render
 * and engine threads read. Every write bumps a generation counter so readers can notice changes cheaply. */
#ifndef GSR_SETTINGS_H
#define GSR_SETTINGS_H
#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
	GSR_S_VIEW_MODE,     /* 0 native 240x160, 1 expanded view 360x240, 2 fit screen (the view takes the screen's shape, up to 480x320) */
	GSR_S_SCALE_MODE,    /* 0 fit (exact aspect), 1 integer, 2 stretch, 3 zoom to fill (crops) */
	GSR_S_FILTER,        /* 0 sharp pixels, 1 smooth */
	GSR_S_SCREEN,        /* 0 raw, 1 unlit, 2 frontlit, 3 backlit, 4 classic, 5..10 colour profiles (runtime::ScreenKind) */
	GSR_S_BLEND,         /* flicker reduction: 0 off, 1 light, 2 medium, 3 strong */
	GSR_S_INTERP,        /* 2x frame interpolation (needs a 120 Hz display) */
	GSR_S_ENH_TIMING,
	GSR_S_NATIVE_RENDER, /* enhanced (GPU) renderer */
	GSR_S_NATIVE_SCALE,  /* 1..10 */
	GSR_S_VOLUME,        /* 0..100 */
	GSR_S_MUTE,
	GSR_S_FF_MULT10,     /* fast-forward speed times ten (20 = 2.0x) */
	GSR_S_FF_MODE,       /* 0 hold, 1 toggle */
	GSR_S_FF_UNCAPPED,
	GSR_S_FF_MUTE,       /* mute while fast forwarding */
	GSR_S_SHOW_FPS,
	GSR_S_TOUCH_SCALE,   /* descore touch scale step 0..8 */
	GSR_S_TOUCH_OPACITY, /* percent, 5..100 */
	GSR_S_RENDERER,      /* 0 auto (GPU for the expanded view), 1 CPU, 2 GPU */
	GSR_S_AUTO_CPU,      /* no longer used (Auto does not switch by itself any more); kept so the setting numbers stay the same */
	GSR_S_TOUCH_DPAD,    /* touch direction control: 0 joystick, 1 D-pad, 2 floating D-pad (wherever the thumb lands on the left half) */
	GSR_S_SCREEN_FILTER, /* 0 off, 1 LCD, 2 CRT (flat), 3 xBR, 4 scanlines (gsr_screen_filter.h) */
	/* Everything from here on is a cheat: kept for this play session only (gsr_settings.c neither saves nor loads
	 * them), so new cheats must be added at the end. */
	GSR_S_CHEAT_HP,      /* cheats: infinite HP */
	GSR_S_CHEAT_PP,      /* cheats: infinite PP */
	GSR_S_CHEAT_EXP,     /* cheats: experience multiplier step 0..8 (0x 0.5x 1x 2x 5x 10x 25x 50x 100x), 2 = normal */
	GSR_S_CHEAT_COIN,    /* cheats: coins multiplier step, same steps */
	GSR_S_CHEAT_DROP,    /* cheats: item drop chance step 0..5 (0x .. 10x), 2 = normal */
	GSR_S_PORTRAIT_COMPACT, /* portrait touch controls: 0 spread over the space below the picture, 1 compact right under it, 2 compact at the bottom */
	GSR_S_VIEW_ZOOM,     /* fit screen view: 0 shows the most of the world (up to 480x320), 10 the closest (the native 240x160 just fills one side) */
	GSR_S_ROOM_CLIP,     /* expanded views, The Lost Age: hide characters outside the current room. 0 off, 1 the rooms upstream checked, 2 every room */
	GSR_S_TOUCH_STYLE,   /* touch buttons: 0 flat (Material), 1 outlined, 2 GBA SP (coloured A/B, dark slate for the rest) */
	GSR_S_TOUCH_COLOR,   /* 0 wallpaper (Material You), 1 grey, 2 white, 3 dark, 4 blue, 5 red, 6 green, 7 purple */
	GSR_S_TOUCH_FADE,    /* fade the controls when idle: 0 never, 1 after 3 s, 2 5 s, 3 10 s, 4 20 s */
	GSR_S_TAP_A,         /* a quick tap on empty screen presses A */
	GSR_S_TWO_FINGER_B,  /* a quick two-finger tap presses B */
	GSR_S_SHOW_A,        /* the on-screen A button */
	GSR_S_SHOW_B,        /* the on-screen B button */
	GSR_S_TURBO,         /* turbo A and turbo B buttons */
	GSR_S_TURBO_RATE,    /* turbo presses per second, 3..20 */
	GSR_S_COUNT
} GsrSetting;

/* Load the saved file (if any) and apply defaults for the rest. dir is the folder to keep it in. */
void gsr_settings_init(const char *dir);
int gsr_settings_get(GsrSetting id);
/* Clamps to the setting's range, saves the file, bumps the generation. */
void gsr_settings_set(GsrSetting id, int value);
unsigned gsr_settings_generation(void);
/* 1 once gsr_settings_init has loaded the saved file: values set before that are replaced by the file's. */
int gsr_settings_ready(void);
/* Cheats are kept for this play session only: never saved or loaded. */
#define GSR_SETTING_IS_CHEAT(i) ((i) >= GSR_S_CHEAT_HP && (i) <= GSR_S_CHEAT_DROP)

#ifdef __cplusplus
}
#endif
#endif
