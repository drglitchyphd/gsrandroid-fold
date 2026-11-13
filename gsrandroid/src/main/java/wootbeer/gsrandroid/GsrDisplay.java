package wootbeer.gsrandroid;

import android.app.Activity;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

/**
 * Display refresh rate handling for 2x frame interpolation. The option only does something when the screen
 * is really running at about 120 Hz, so: find the fastest mode at the current resolution, ask for it while
 * the option is on (and let the system choose again when it is off), and tell the native side what rate the
 * display is running at now.
 */
final class GsrDisplay {
	private GsrDisplay() {}

	/** Highest refresh rate this screen offers at its current resolution (the current rate if it cannot tell). */
	static float maxRefresh(Activity a) {
		Display d = a.getWindowManager().getDefaultDisplay();
		float best = d.getRefreshRate();
		if (Build.VERSION.SDK_INT >= 23) {
			Display.Mode cur = d.getMode();
			for (Display.Mode m : d.getSupportedModes()) {
				if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight()
						&& m.getRefreshRate() > best) {
					best = m.getRefreshRate();
				}
			}
		}
		return best;
	}

	/** Ask for the fastest mode while interpolation is on; otherwise drop the request. */
	static void apply(final Activity a) {
		if (Build.VERSION.SDK_INT < 23) return;
		final boolean want = GsrNative.getSetting(GsrNative.INTERP) != 0;
		a.runOnUiThread(new Runnable() {
			@Override public void run() {
				Display d = a.getWindowManager().getDefaultDisplay();
				int id = 0;
				if (want) {
					Display.Mode cur = d.getMode();
					float best = 0;
					for (Display.Mode m : d.getSupportedModes()) {
						if (m.getPhysicalWidth() == cur.getPhysicalWidth()
								&& m.getPhysicalHeight() == cur.getPhysicalHeight() && m.getRefreshRate() > best) {
							best = m.getRefreshRate();
							id = m.getModeId();
						}
					}
				}
				WindowManager.LayoutParams lp = a.getWindow().getAttributes();
				if (lp.preferredDisplayModeId != id) {
					lp.preferredDisplayModeId = id;
					a.getWindow().setAttributes(lp);
				}
				publish(a);
			}
		});
	}

	// ---- Orientation: Auto (follow the device, the default), Landscape or Portrait ----------------------------------

	private static final String PREFS = "gsr_display", KEY_ORIENTATION = "orientation";
	static final int ORIENT_AUTO = 0, ORIENT_LANDSCAPE = 1, ORIENT_PORTRAIT = 2;

	/** Auto turns with the device (and the system's rotation lock), on either screen of a foldable. */
	static int orientationMode(Activity a) {
		return a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getInt(KEY_ORIENTATION, ORIENT_AUTO);
	}

	static void setOrientationMode(Activity a, int mode) {
		a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putInt(KEY_ORIENTATION, mode).apply();
		applyOrientation(a);
	}

	static final String FLAG_PAUSE_ON_FOLD = "pause_on_fold", FLAG_PAUSE_ON_FOCUS = "pause_on_focus";

	static boolean flag(Activity a, String key, boolean fallback) {
		return a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getBoolean(key, fallback);
	}

	static void setFlag(Activity a, String key, boolean on) {
		a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putBoolean(key, on).apply();
	}

	/** True while the screen is upright (taller than wide): the picture at the top, the controls below it. */
	static boolean portrait(Activity a) {
		return a.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
	}

	/** Portrait: tell the native side how far down a camera cutout reaches, so the picture starts below it. */
	static void publishTopInset(final Activity a) {
		if (Build.VERSION.SDK_INT < 28) return;
		final android.view.View d = a.getWindow().getDecorView();
		d.postDelayed(new Runnable() {
			@Override public void run() {
				int top = 0;
				android.view.WindowInsets wi = d.getRootWindowInsets();
				if (wi != null && wi.getDisplayCutout() != null) top = wi.getDisplayCutout().getSafeInsetTop();
				try {
					GsrNative.setTopInset(top);
				} catch (Throwable ignored) {
					// native library not loaded yet
				}
			}
		}, 300);
	}

	/** Auto: any way up the device is held; otherwise landscape either way up, or upright portrait. */
	static void applyOrientation(Activity a) {
		int mode = orientationMode(a);
		a.setRequestedOrientation(mode == ORIENT_PORTRAIT
				? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
				: mode == ORIENT_LANDSCAPE
						? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
						: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
	}

	/** Tell the native side the refresh rate the display is running at right now. */
	static void publish(Activity a) {
		try {
			GsrNative.setDisplayRefresh(a.getWindowManager().getDefaultDisplay().getRefreshRate());
		} catch (Throwable ignored) {
			// native library not loaded yet
		}
	}
}
