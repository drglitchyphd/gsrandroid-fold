package wootbeer.gsrandroid;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import wootbeer.descore.DescoreTheme;
import wootbeer.descore.DescoreWidgets;

/**
 * The startup / first-run build screen, in the Material You colours of the launcher and menu (DescoreTheme): the
 * game's name, what is happening, a progress bar and notes, in a column centred on the screen that never gets wider
 * than suits reading, so it lays out the same on a tall cover screen, a nearly square inner screen or sideways.
 * It sits on top of the game surface and goes away by itself once the game is drawing. All the state comes from
 * the native side ({@link GsrNative#buildInfo()}).
 */
final class GsrBuildOverlay {
	private final Activity activity;
	private final DescoreTheme t;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final FrameLayout root;
	private final TextView label, title, detail, percent, note, error;
	private final DescoreWidgets.Progress bar;
	private boolean done;

	private final Runnable tick = new Runnable() {
		@Override public void run() {
			if (done) return;
			update();
			if (!done) handler.postDelayed(this, 250);
		}
	};

	/** The game this screen builds and plays (the launcher's unit). */
	private String gameName() {
		int unit = GsrConfig.unitOf(activity);
		return GsrConfig.title(GsrConfig.isTla(unit), GsrConfig.lang(unit));
	}

	GsrBuildOverlay(Activity a) {
		activity = a;
		t = DescoreTheme.get(a);
		root = new FrameLayout(a);
		root.setBackgroundColor(t.surface);
		// Never take focus or touches from the game view underneath (gamepad keys must reach it).
		root.setFocusable(false);
		root.setClickable(false);

		DescoreWidgets.Column col = new DescoreWidgets.Column(a);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setGravity(Gravity.CENTER_HORIZONTAL);
		col.setMaxSize(dp(560), 0);
		col.setFocusable(false);
		int pad = dp(24);
		col.setPadding(pad, pad, pad, pad);
		FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
		root.addView(col, cp);

		label = text(col, t.primary, 14, 0, true);
		label.setText("Golden Sun Recompiled");
		title = text(col, t.onSurface, 24, 8, true);
		detail = text(col, t.onSurfaceVariant, 15, 12, false);
		bar = new DescoreWidgets.Progress(a, t);
		bar.setMax(1000);
		LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		bp.topMargin = dp(24);
		col.addView(bar, bp);
		percent = text(col, t.onSurfaceVariant, 14, 10, true);
		note = box(col, t.secondaryContainer, t.onSecondaryContainer);
		error = box(col, t.errorContainer, t.onErrorContainer);
	}

	/** A message in a rounded tonal box (a note, or an error). */
	private TextView box(LinearLayout parent, int bg, int fg) {
		TextView v = new TextView(activity);
		v.setTextColor(fg);
		v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		v.setGravity(Gravity.CENTER);
		v.setBackground(t.rounded(bg, 16));
		v.setPadding(dp(16), dp(12), dp(16), dp(12));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(20);
		parent.addView(v, lp);
		return v;
	}

	private TextView text(LinearLayout parent, int color, float size, int topDp, boolean medium) {
		TextView v = new TextView(activity);
		v.setTextColor(color);
		v.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
		v.setGravity(Gravity.CENTER);
		v.setPadding(0, dp(topDp), 0, 0);
		v.setLineSpacing(0, 1.1f);
		if (medium) v.setTypeface(DescoreTheme.medium());
		parent.addView(v);
		return v;
	}

	private int dp(float v) {
		return t.dp(v);
	}

	void show() {
		activity.addContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		update();
		handler.postDelayed(tick, 250);
	}

	void stop() {
		done = true;
		handler.removeCallbacks(tick);
	}

	private static String clock(int sec) {
		return (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
	}

	private void set(TextView t, String s) {
		if (s == null || s.length() == 0) {
			t.setVisibility(View.GONE);
		} else {
			t.setText(s);
			t.setVisibility(View.VISIBLE);
		}
	}

	private void update() {
		String[] i;
		try {
			i = GsrNative.buildInfo();
		} catch (Throwable t) {
			return; // native library not ready yet
		}
		if (i == null || i.length < 8) return;
		String phase = i[0];
		if ("running".equals(phase)) {
			root.setVisibility(View.GONE);
			stop();
			return;
		}
		root.setVisibility(View.VISIBLE);
		int progress = 0, elapsed = 0;
		try { progress = Integer.parseInt(i[3]); } catch (NumberFormatException ignored) {}
		try { elapsed = Integer.parseInt(i[4]); } catch (NumberFormatException ignored) {}
		boolean cached = "1".equals(i[5]);

		bar.setVisibility(View.GONE);
		set(percent, null);
		set(note, null);
		set(error, null);
		set(detail, null);

		if ("rom_missing".equals(phase)) {
			title.setTextColor(t.error);
			set(title, "No usable ROM found");
			set(detail, "Restart the app and pick your " + gameName() + " (USA or Europe) .gba file.");
		} else if ("rom_wrong".equals(phase)) {
			title.setTextColor(t.error);
			set(title, "This is not the supported ROM");
			set(detail, gameName() + " (USA or Europe) is required. Press Back to open the menu and choose Change ROM.");
		} else if ("failed".equals(phase)) {
			title.setTextColor(t.error);
			set(title, "Building the game failed");
			set(error, i[2]);
			set(detail, "Log: " + i[6]);
			set(note, "Close and reopen the app to try again.");
		} else if ("engine_error".equals(phase)) {
			title.setTextColor(t.error);
			set(title, "The game engine could not start");
			set(error, i[2]);
		} else if ("engine_stopped".equals(phase)) {
			title.setTextColor(t.error);
			set(title, "The game engine stopped (code " + i[7] + ")");
			set(detail, "Close and reopen the app. If this keeps happening, the log in the app folder says why.");
		} else if ("ready".equals(phase)) {
			title.setTextColor(t.onSurface);
			set(title, "Starting the game");
			set(detail, (cached ? "Loaded from the saved build" : "Built") + " in " + clock(elapsed) + ".");
		} else { // building / waiting
			title.setTextColor(t.onSurface);
			set(title, cached ? "Loading " + gameName() : "Preparing " + gameName() + " for this device");
			set(detail, i[1]);
			bar.setProgress(progress);
			bar.setVisibility(View.VISIBLE);
			set(percent, (progress / 10) + "%    " + clock(elapsed));
			if (!cached) set(note, "This only happens once. It can take 10 minutes or more. Keep the device awake.");
		}
	}
}
