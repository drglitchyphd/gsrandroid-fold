package wootbeer.gsrandroid;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

import wootbeer.descore.DescoreGameActivity;
import wootbeer.descore.DescoreGameConfig;

/**
 * Golden Sun Recompiled's game screen: the shared descore game shell, configured by {@link GsrConfig}.
 *
 * Before the native game starts, this makes sure the translator's data files (addresses and hashes
 * only, no game code or ROM bytes) are unpacked from the APK, and starts {@link GsrBuildService} so the
 * first-run build (translate + compile on the device) keeps running if the player leaves the app.
 */
public class GsrGameActivity extends DescoreGameActivity {
	private static final DescoreGameConfig CONFIG = GsrConfig.create();

	private GsrMenu menu;
	private GsrBuildOverlay overlay;
	private GsrSession session;
	private final Handler handler = new Handler(Looper.getMainLooper());
	/** "Paused, tap to resume" after folding or unfolding; null while not shown. */
	private android.view.View pauseOverlay;
	/** Paused because the window lost focus (the notification shade), resumed when it comes back. */
	private boolean focusPaused;
	private final boolean[] screenChanged = new boolean[1];
	private final Runnable applyProfile = new Runnable() {
		@Override public void run() {
			handler.removeCallbacks(this);
			// The native settings load when the game starts; until then try again shortly.
			if (!GsrProfile.apply(GsrGameActivity.this, screenChanged)) {
				handler.postDelayed(this, 250);
				return;
			}
			if (screenChanged[0] && engineStarted()
					&& GsrDisplay.flag(GsrGameActivity.this, GsrDisplay.FLAG_PAUSE_ON_FOLD, true)) {
				showPauseOverlay();
			}
			if (menu != null) menu.screenChanged();
		}
	};
	private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
		@Override public void onDisplayAdded(int id) {}
		@Override public void onDisplayRemoved(int id) {}
		@Override public void onDisplayChanged(int id) { GsrDisplay.publish(GsrGameActivity.this); }
	};

	@Override
	protected void onResume() {
		super.onResume();
		DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
		if (dm != null) dm.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
		GsrDisplay.publish(this);
		GsrDisplay.apply(this);
		GsrDisplay.publishTopInset(this);
		applyProfile.run();
	}

	@Override
	protected void onPause() {
		DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
		if (dm != null) dm.unregisterDisplayListener(displayListener);
		super.onPause();
	}

	@Override
	protected void onMenuRequested() {
		if (menu == null) menu = new GsrMenu(this);
		menu.show();
	}

	@Override
	protected DescoreGameConfig getConfig() {
		return CONFIG;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		GsrBuilderData.ensureExtracted(this);
		// The build can take ten minutes; do not let the screen sleep while it is on screen.
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		super.onCreate(savedInstanceState);
		GsrDisplay.applyOrientation(this);
		// Creates Android/data/<package>/files on shared storage: the build log (gsr_build_log.txt) is written
		// there, and Android only makes that folder when the app asks for it.
		getExternalFilesDir(null);
		try {
			// Diagnostics (logcat statistics, engine log forwarding) only in debuggable builds; never for players.
			GsrNative.setDebug((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
		} catch (Throwable ignored) {
			// native library not loaded yet
		}
		overlay = new GsrBuildOverlay(this);
		overlay.show();
		session = new GsrSession(this);
		session.start();
		Intent service = new Intent(this, GsrBuildService.class);
		try {
			if (Build.VERSION.SDK_INT >= 26) {
				startForegroundService(service);
			} else {
				startService(service);
			}
		} catch (RuntimeException e) {
			// Not being allowed to start a service must not stop the game; the build still runs.
		}
	}

	/** True once the game engine has started (it lives in this process, not in the Activity). */
	private static boolean engineStarted() {
		try {
			String phase = GsrNative.buildInfo()[0];
			return "running".equals(phase) || "engine_stopped".equals(phase) || "engine_error".equals(phase);
		} catch (Throwable t) {
			return false;
		}
	}

	@Override
	public void onConfigurationChanged(android.content.res.Configuration c) {
		super.onConfigurationChanged(c);
		GsrDisplay.publishTopInset(this);  // the cutout's inset changes when the screen turns
		// Folding, unfolding or turning: this screen's own view and touch settings (GsrProfile).
		applyProfile.run();
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (!hasFocus) {
			// Fingers on the screen when the shade came down never send their lift: let go of everything.
			try {
				GsrNative.releaseAll();
			} catch (Throwable ignored) {
				// native library not loaded yet
			}
			if (engineStarted() && GsrDisplay.flag(this, GsrDisplay.FLAG_PAUSE_ON_FOCUS, true)) {
				focusPaused = true;
				GsrNative.setUserPause(true);
			}
		} else if (focusPaused) {
			focusPaused = false;
			if (pauseOverlay == null) GsrNative.setUserPause(false);
		}
	}

	/** Folding or unfolding paused the game: a dimmed screen with a resume button; any tap, A or Start resumes. */
	private void showPauseOverlay() {
		GsrNative.setUserPause(true);
		if (pauseOverlay != null) return;
		wootbeer.descore.DescoreTheme t = wootbeer.descore.DescoreTheme.get(this);
		android.widget.FrameLayout root = new android.widget.FrameLayout(this);
		root.setBackgroundColor(t.scrim);
		root.setClickable(true);
		root.setOnClickListener(new android.view.View.OnClickListener() {
			@Override public void onClick(android.view.View v) { hidePauseOverlay(); }
		});
		android.widget.TextView pill = new android.widget.TextView(this);
		pill.setText("\u25B6  Paused \u00B7 tap to resume");
		pill.setTextColor(t.onPrimaryContainer);
		pill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
		pill.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
		pill.setBackground(t.pressable(t.primaryContainer, 28));
		pill.setPadding(t.dp(28), t.dp(16), t.dp(28), t.dp(16));
		pill.setClickable(true);
		pill.setOnClickListener(new android.view.View.OnClickListener() {
			@Override public void onClick(android.view.View v) { hidePauseOverlay(); }
		});
		root.addView(pill, new android.widget.FrameLayout.LayoutParams(
				android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
				android.view.Gravity.CENTER));
		addContentView(root, new android.view.ViewGroup.LayoutParams(
				android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
		pauseOverlay = root;
	}

	private void hidePauseOverlay() {
		if (pauseOverlay != null) {
			((android.view.ViewGroup) pauseOverlay.getParent()).removeView(pauseOverlay);
			pauseOverlay = null;
		}
		if (!focusPaused) GsrNative.setUserPause(false);
		android.view.View game = findViewById(android.R.id.content);
		if (game instanceof android.view.ViewGroup && ((android.view.ViewGroup) game).getChildCount() > 0)
			((android.view.ViewGroup) game).getChildAt(0).requestFocus();
	}

	@Override
	public boolean dispatchKeyEvent(android.view.KeyEvent e) {
		if (pauseOverlay != null) {
			int k = e.getKeyCode();
			if (k == android.view.KeyEvent.KEYCODE_BUTTON_A || k == android.view.KeyEvent.KEYCODE_BUTTON_START
					|| k == android.view.KeyEvent.KEYCODE_ENTER || k == android.view.KeyEvent.KEYCODE_DPAD_CENTER
					|| k == android.view.KeyEvent.KEYCODE_SPACE) {
				if (e.getAction() == android.view.KeyEvent.ACTION_UP) hidePauseOverlay();
				return true;  // the press that resumes does not also reach the game
			}
		}
		return super.dispatchKeyEvent(e);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == GsrSession.REQUEST_PICK_SAVE && session != null) session.onPickResult(resultCode, data);
		else if (menu != null) menu.onActivityResult(requestCode, resultCode, data);
	}

	@Override
	protected void onDestroy() {
		handler.removeCallbacks(applyProfile);
		if (overlay != null) overlay.stop();
		if (session != null) session.stop();
		super.onDestroy();
		// The engine thread belongs to the process. If the game screen goes away (swiped out of the recent apps, or
		// dropped by the system) the process can linger with the old engine still in it, and pressing Play again would
		// start a SECOND engine beside it: slow, with broken audio. Once the game has started there is nothing worth
		// keeping, so end the process, as "Exit game" does. Rotation and similar changes are handled by configChanges.
		if (!isChangingConfigurations() && engineStarted()) android.os.Process.killProcess(android.os.Process.myPid());
	}
}
