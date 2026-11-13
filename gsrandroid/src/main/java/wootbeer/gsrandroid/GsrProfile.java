package wootbeer.gsrandroid;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * Settings remembered separately for each screen and orientation. A foldable has two screens, the inner one
 * (nearly square, tablet sized) and the cover one (tall and narrow), and each can be held either way: four
 * profiles, {@code inner_landscape}, {@code inner_portrait}, {@code outer_landscape} and {@code outer_portrait}.
 * An ordinary phone only ever uses the two {@code outer_} profiles and a tablet the two {@code inner_} ones.
 *
 * The native side (gsr_settings.c) keeps one value per setting, the one in use. For the settings listed in
 * {@link #PER_SCREEN} this class keeps a copy per profile, and when the screen changes (folding, unfolding,
 * turning) it puts the new profile's values in place. A profile seen for the first time starts from the values
 * in use, except the view, which starts as Fit screen with a zoom suited to the screen's size.
 */
final class GsrProfile {
	private static final String PREFS = "gsr_profiles";

	/** The settings kept per screen: the view and how it is scaled, and the touch controls. */
	static final int[] PER_SCREEN = {
			GsrNative.VIEW_MODE, GsrNative.VIEW_ZOOM, GsrNative.SCALE_MODE, GsrNative.FILTER, GsrNative.SCREEN_FILTER,
			GsrNative.TOUCH_SCALE, GsrNative.TOUCH_OPACITY, GsrNative.TOUCH_DPAD, GsrNative.PORTRAIT_COMPACT };

	/** Fit screen's zoom for a new profile: further out on the big inner screen, closer on the small cover one. */
	private static final int ZOOM_INNER = 3, ZOOM_OUTER = 7;

	private static Context app;
	/** The profile whose values are in place on the native side, or null before the first apply. */
	private static String applied;

	private GsrProfile() {}

	/** Smallest width of 600 dp or more: a tablet-sized screen (a foldable's inner screen). */
	static boolean inner(Configuration c) { return c.smallestScreenWidthDp >= 600; }

	static boolean portrait(Configuration c) { return c.orientation == Configuration.ORIENTATION_PORTRAIT; }

	static String key(Configuration c) {
		return (inner(c) ? "inner_" : "outer_") + (portrait(c) ? "portrait" : "landscape");
	}

	/** For the menu: which screen the settings marked "this screen" belong to. */
	static String label(Activity a) {
		Configuration c = a.getResources().getConfiguration();
		return (inner(c) ? "Inner screen" : "Cover screen") + ", " + (portrait(c) ? "portrait" : "landscape");
	}

	static boolean perScreen(int id) {
		for (int s : PER_SCREEN) if (s == id) return true;
		return false;
	}

	private static SharedPreferences prefs() { return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

	/** Called by GsrNative.setSetting: keep a per-screen setting's new value for the current profile. */
	static void remember(int id, int value) {
		if (app == null || applied == null || !perScreen(id)) return;
		prefs().edit().putInt(applied + "." + id, value).apply();
	}

	/**
	 * Puts the current screen's values in place. Returns false while the native settings are not loaded yet (the
	 * caller tries again shortly), true once applied or when nothing changed. {@code changedScreen} is set to
	 * whether the screen kind (inner / cover) differs from the one applied before (not for the first apply).
	 */
	static boolean apply(Activity a, boolean[] changedScreen) {
		app = a.getApplicationContext();
		if (changedScreen != null && changedScreen.length > 0) changedScreen[0] = false;
		boolean ready;
		try {
			ready = GsrNative.settingsReady();
		} catch (Throwable t) {
			return false;  // native library not loaded yet
		}
		if (!ready) return false;
		Configuration c = a.getResources().getConfiguration();
		String key = key(c);
		if (key.equals(applied)) return true;
		if (applied != null && changedScreen != null && changedScreen.length > 0)
			changedScreen[0] = !applied.substring(0, 5).equals(key.substring(0, 5));
		SharedPreferences p = prefs();
		SharedPreferences.Editor e = p.edit();
		for (int id : PER_SCREEN) {
			String k = key + "." + id;
			int v;
			if (p.contains(k)) {
				v = p.getInt(k, 0);
			} else if (id == GsrNative.VIEW_MODE) {
				v = 2;  // Fit screen
			} else if (id == GsrNative.VIEW_ZOOM) {
				v = inner(c) ? ZOOM_INNER : ZOOM_OUTER;
			} else {
				v = GsrNative.getSetting(id);
			}
			GsrNative.setSettingRaw(id, v);
			e.putInt(k, GsrNative.getSetting(id));
		}
		e.apply();
		applied = key;
		return true;
	}

	/** Forget every screen's values (Settings > Reset this screen does one, this does all). */
	static void resetCurrent(Activity a) {
		app = a.getApplicationContext();
		String key = key(a.getResources().getConfiguration());
		SharedPreferences.Editor e = prefs().edit();
		for (int id : PER_SCREEN) e.remove(key + "." + id);
		e.apply();
		applied = null;
		apply(a, null);
	}
}
