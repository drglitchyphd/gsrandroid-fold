package wootbeer.descore;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;

/**
 * Material You colours for a descore port's own screens (launcher, side menu, pause overlay), in the dark scheme (it sits
 * over or beside a game). On Android 12 and later the colours come from the wallpaper (the system's tonal
 * palettes); before that from a fixed blue palette close to the app's old accent. No libraries: the roles are the
 * Material 3 dark-scheme roles, read from the system palette colours by tone.
 */
public final class DescoreTheme {
	public final int primary, onPrimary, primaryContainer, onPrimaryContainer;
	public final int secondaryContainer, onSecondaryContainer;
	public final int surface, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest;
	public final int onSurface, onSurfaceVariant, outline, outlineVariant;
	/** A dark veil drawn over the game behind a sheet or dialog. */
	public final int scrim = 0x99000000;
	public final float density;

	private DescoreTheme(Context c, int[] v) {
		primary = v[0]; onPrimary = v[1]; primaryContainer = v[2]; onPrimaryContainer = v[3];
		secondaryContainer = v[4]; onSecondaryContainer = v[5];
		surface = v[6]; surfaceContainerLow = v[7]; surfaceContainer = v[8]; surfaceContainerHigh = v[9];
		surfaceContainerHighest = v[10]; onSurface = v[11]; onSurfaceVariant = v[12]; outline = v[13];
		outlineVariant = v[14];
		density = c.getResources().getDisplayMetrics().density;
	}

	public static DescoreTheme get(Context c) {
		if (Build.VERSION.SDK_INT >= 31) {
			try {
				return new DescoreTheme(c, dynamic(c));
			} catch (RuntimeException ignored) {
				// a vendor build without the palette colours: the fixed palette below
			}
		}
		return new DescoreTheme(c, new int[] {
				0xFFA8C8FF, 0xFF003060, 0xFF1F4777, 0xFFD5E3FF,
				0xFF3C4858, 0xFFD7E3F8,
				0xFF111318, 0xFF191C20, 0xFF1D2024, 0xFF282A2F, 0xFF33353A,
				0xFFE2E2E9, 0xFFC4C6D0, 0xFF8E9099, 0xFF44474F });
	}

	@android.annotation.TargetApi(31)
	private static int[] dynamic(Context c) {
		android.content.res.Resources r = c.getResources();
		int a1_100 = r.getColor(android.R.color.system_accent1_100, null);
		int a1_200 = r.getColor(android.R.color.system_accent1_200, null);
		int a1_700 = r.getColor(android.R.color.system_accent1_700, null);
		int a1_800 = r.getColor(android.R.color.system_accent1_800, null);
		int a2_100 = r.getColor(android.R.color.system_accent2_100, null);
		int a2_700 = r.getColor(android.R.color.system_accent2_700, null);
		int n1_100 = r.getColor(android.R.color.system_neutral1_100, null);
		int n1_800 = r.getColor(android.R.color.system_neutral1_800, null);
		int n1_900 = r.getColor(android.R.color.system_neutral1_900, null);
		int n2_200 = r.getColor(android.R.color.system_neutral2_200, null);
		int n2_400 = r.getColor(android.R.color.system_neutral2_400, null);
		int n2_700 = r.getColor(android.R.color.system_neutral2_700, null);
		// The surface containers sit between tone 10 (900) and tone 20 (800) in Material 3's dark scheme.
		return new int[] {
				a1_200, a1_800, a1_700, a1_100,
				a2_700, a2_100,
				blend(n1_900, 0xFF000000, 0.35f), n1_900, blend(n1_900, n1_800, 0.3f), blend(n1_900, n1_800, 0.65f), n1_800,
				n1_100, n2_200, n2_400, n2_700 };
	}

	public static int blend(int a, int b, float t) {
		int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
	}

	public static int alpha(int color, float a) {
		return (Math.round(a * 255) << 24) | (color & 0x00FFFFFF);
	}

	public int dp(float v) { return (int) (v * density + 0.5f); }

	/** A filled shape with rounded corners (radius in dp). */
	public GradientDrawable rounded(int color, float radiusDp) {
		GradientDrawable d = new GradientDrawable();
		d.setColor(color);
		d.setCornerRadius(dp(radiusDp));
		return d;
	}

	/** A rounded shape with a press ripple (and the same shape as the ripple's mask). */
	public Drawable pressable(int color, float radiusDp) {
		return new RippleDrawable(ColorStateList.valueOf(alpha(onSurface, 0.12f)), rounded(color, radiusDp),
				rounded(0xFFFFFFFF, radiusDp));
	}
}
