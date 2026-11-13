package wootbeer.descore;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.TypedValue;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared Storage Access Framework asset picker + "is my game data ready" screen for every
 * descore-shell port. Subclasses only say which {@link DescoreGameConfig} describes their game and
 * which {@link DescoreGameActivity} subclass to start; everything else - the folder prompt, the
 * recursive folder scan, the verified copy into private storage, the ready/missing status screen
 * and the Play buttons - lives here so every port built on descore gets the same behavior and
 * the same bug fixes.
 *
 * Two things set it apart from a game-specific picker: the folder scan is recursive (a player can
 * point at a whole game folder and we find the files wherever they are, first match wins), and what
 * to copy comes from the config instead of being hardcoded per game.
 */
public abstract class DescoreLauncherActivity extends Activity {
	private static final String TAG = "DescoreLauncher";
	private static final int REQUEST_CODE_OPEN_DATA_FOLDER = 4242;
	/** Deepest sub-folder level the recursive scan will descend into below the picked folder. */
	private static final int MAX_SCAN_DEPTH = 6;
	/** Safety valve so picking something enormous (a whole drive) can't scan forever. */
	private static final int MAX_SCAN_ENTRIES = 30000;

	protected abstract DescoreGameConfig getConfig();

	protected abstract Class<? extends DescoreGameActivity> getGameActivityClass();

	/** Intent extra: show the picker even when "start the last game straight away" is on (a game's
	 * "Switch game" menu item opens the launcher with it). */
	public static final String EXTRA_SHOW_PICKER = "descore_show_picker";
	private static final String PREFS = "descore_launcher", KEY_LAST_UNIT = "last_unit", KEY_AUTO_LAST = "auto_last";

	private File rootDir;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setImmersive();
		if (Build.VERSION.SDK_INT >= 19) {
			getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(
					new View.OnSystemUiVisibilityChangeListener() {
						@Override
						public void onSystemUiVisibilityChange(int visibility) {
							if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
								setImmersive();
							}
						}
					});
		}
		rootDir = new File(getFilesDir(), getConfig().rootDirName);
		if (launchIfComplete() || launchLastUnit()) {
			return;
		}
		if (anyUnitReady()) {
			showUnitSelect(null);
		} else {
			showDataPicker(null);
		}
	}

	/**
	 * Single-unit games that opt in (DescoreGameConfig.autoLaunchWhenComplete): once every required
	 * and optional file is present, skip the picker/status screens and go straight into the game.
	 * The launcher finishes itself, so quitting the game returns to the home screen rather than here.
	 */
	private boolean launchIfComplete() {
		DescoreGameConfig cfg = getConfig();
		if (!cfg.autoLaunchWhenComplete || cfg.units.size() != 1) return false;
		DescoreGameConfig.Unit unit = cfg.units.get(0);
		if (!isUnitReady(unit) || presentOptional(unit).size() != unit.optionalFiles.length) return false;
		Intent intent = new Intent(this, getGameActivityClass());
		intent.putExtra(DescoreGameActivity.EXTRA_UNIT_ID, unit.id);
		startActivity(intent);
		finish();
		return true;
	}

	/** With "start the last game straight away" on: skip the picker and play the last unit played. */
	private boolean launchLastUnit() {
		if (getIntent().getBooleanExtra(EXTRA_SHOW_PICKER, false)) return false;
		android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
		if (!prefs.getBoolean(KEY_AUTO_LAST, false)) return false;
		for (DescoreGameConfig.Unit unit : getConfig().units) {
			if (unit.id == prefs.getInt(KEY_LAST_UNIT, -1) && isUnitReady(unit)) {
				play(unit);
				return true;
			}
		}
		return false;
	}

	private void play(DescoreGameConfig.Unit unit) {
		getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_LAST_UNIT, unit.id).apply();
		Intent intent = new Intent(this, getGameActivityClass());
		intent.putExtra(DescoreGameActivity.EXTRA_UNIT_ID, unit.id);
		startActivity(intent);
		// The picker does not stay behind the game, so swiping the game away in the recent apps closes the
		// app instead of leaving the picker there.
		finish();
	}

	// --- Readiness ---------------------------------------------------------------------------

	private File unitDir(DescoreGameConfig.Unit unit) {
		return new File(rootDir, unit.dirName);
	}

	private boolean isUnitReady(DescoreGameConfig.Unit unit) {
		return missingFiles(unit).isEmpty();
	}

	private boolean anyUnitReady() {
		for (DescoreGameConfig.Unit u : getConfig().units) {
			if (isUnitReady(u)) return true;
		}
		return false;
	}

	private List<String> missingFiles(DescoreGameConfig.Unit unit) {
		List<String> missing = new ArrayList<>();
		File dir = unitDir(unit);
		for (String name : unit.requiredFiles) {
			File f = new File(dir, name);
			if (!f.exists() || f.length() <= 0) missing.add(name);
		}
		return missing;
	}

	/** Optional files that did make it into private storage, for the status line. */
	private List<String> presentOptional(DescoreGameConfig.Unit unit) {
		List<String> present = new ArrayList<>();
		File dir = unitDir(unit);
		for (String name : unit.optionalFiles) {
			File f = new File(dir, name);
			if (f.exists() && f.length() > 0) present.add(name);
		}
		return present;
	}

	// --- UI -----------------------------------------------------------------------------------
	//
	// Material You (dark): the screens are rebuilt from scratch whenever they change and whenever the window
	// changes size (the activity handles rotation and folding itself, see onConfigurationChanged), so each layout
	// decision (one column or a grid, how wide the content may be) is made for the current window.

	private static final int SCREEN_NONE = 0, SCREEN_PICKER = 1, SCREEN_COPYING = 2, SCREEN_SELECT = 3;
	private static final int BUTTON_FILLED = 0, BUTTON_TONAL = 1, BUTTON_OUTLINED = 2;
	/** The screen on show and its message (an error, or what was copied), to build it again after a resize. */
	private int screen = SCREEN_NONE;
	private String screenMessage;
	private DescoreTheme t;

	private DescoreTheme theme() {
		if (t == null) t = DescoreTheme.get(this);
		return t;
	}

	private int dp(float v) {
		return theme().dp(v);
	}

	private int widthDp() {
		return getResources().getConfiguration().screenWidthDp;
	}

	private static void noFocusHighlight(View v) {
		if (Build.VERSION.SDK_INT >= 26) v.setDefaultFocusHighlightEnabled(false);
	}

	/**
	 * A new screen: a scrolling page in the surface colour, with a column no wider than {@code maxWidthDp},
	 * centred on the page ({@code center}) or at its top.
	 */
	private LinearLayout newScreen(int maxWidthDp, boolean center) {
		DescoreTheme t = theme();
		getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(t.surface));
		android.widget.ScrollView scroll = new android.widget.ScrollView(this);
		scroll.setFillViewport(true);
		scroll.setBackgroundColor(t.surface);
		scroll.setVerticalScrollBarEnabled(false);
		noFocusHighlight(scroll);
		android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
		DescoreWidgets.Column col = new DescoreWidgets.Column(this);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setMaxSize(dp(maxWidthDp), 0);
		int side = dp(widthDp() >= 600 ? 32 : 16);
		frame.setPadding(side, dp(24), side, dp(24));
		frame.addView(col, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, center ? Gravity.CENTER : Gravity.TOP | Gravity.CENTER_HORIZONTAL));
		scroll.addView(frame, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		setContentView(scroll);
		return col;
	}

	private TextView addText(LinearLayout parent, CharSequence text, int color, float sp, int topPadDp) {
		TextView tv = new TextView(this);
		tv.setText(text);
		tv.setTextColor(color);
		tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		tv.setPadding(0, dp(topPadDp), 0, 0);
		tv.setLineSpacing(0, 1.1f);
		parent.addView(tv);
		return tv;
	}

	private TextView addTitle(LinearLayout parent, CharSequence text, int color, float sp, int topPadDp) {
		TextView tv = addText(parent, text, color, sp, topPadDp);
		tv.setTypeface(DescoreTheme.medium());
		return tv;
	}

	/** A message box: an error (error container) or good news (secondary container). */
	private void addBanner(LinearLayout parent, String text, boolean error) {
		DescoreTheme t = theme();
		TextView tv = new TextView(this);
		tv.setText(text);
		tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		tv.setTextColor(error ? t.onErrorContainer : t.onSecondaryContainer);
		tv.setBackground(t.rounded(error ? t.errorContainer : t.secondaryContainer, 16));
		tv.setPadding(dp(16), dp(12), dp(16), dp(12));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(16);
		parent.addView(tv, lp);
	}

	/** An M3 button (filled, tonal or outlined), focusable for a gamepad, with an optional leading icon. */
	private LinearLayout makeButton(String text, int icon, int style, View.OnClickListener click) {
		DescoreTheme t = theme();
		LinearLayout b = new LinearLayout(this);
		b.setOrientation(LinearLayout.HORIZONTAL);
		b.setGravity(Gravity.CENTER);
		b.setFocusable(true);
		b.setClickable(true);
		noFocusHighlight(b);
		b.setMinimumHeight(dp(style == BUTTON_FILLED ? 56 : 48));
		b.setPadding(dp(24), dp(8), dp(24), dp(8));
		int fg;
		if (style == BUTTON_FILLED) {
			fg = t.onPrimary;
			b.setBackground(t.focusRing(t.primary, t.onSurface, 28));
		} else if (style == BUTTON_TONAL) {
			fg = t.onSecondaryContainer;
			b.setBackground(t.focusRing(t.secondaryContainer, t.primary, 28));
		} else {
			fg = t.primary;
			android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
			s.addState(new int[] { android.R.attr.state_focused },
					t.outlined(DescoreTheme.alpha(t.primary, 0.12f), t.primary, 3, 28));
			s.addState(new int[0], t.outlined(android.graphics.Color.TRANSPARENT, t.outline, 1, 28));
			b.setBackground(new android.graphics.drawable.RippleDrawable(
					android.content.res.ColorStateList.valueOf(DescoreTheme.alpha(t.primary, 0.12f)), s,
					t.rounded(0xFFFFFFFF, 28)));
		}
		if (icon >= 0) {
			DescoreWidgets.Icon i = new DescoreWidgets.Icon(this, t, icon, 20, fg);
			LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			ip.rightMargin = dp(8);
			b.addView(i, ip);
		}
		TextView tv = new TextView(this);
		tv.setText(text);
		tv.setTextColor(fg);
		tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, style == BUTTON_FILLED ? 16 : 14);
		tv.setTypeface(DescoreTheme.medium());
		tv.setGravity(Gravity.CENTER);
		b.addView(tv);
		b.setOnClickListener(click);
		return b;
	}

	/** A small chip (a unit's language, its state). */
	private TextView chip(String text, int fg, int bg, int stroke) {
		TextView c = new TextView(this);
		c.setText(text);
		c.setTextColor(fg);
		c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
		c.setTypeface(DescoreTheme.medium());
		c.setBackground(stroke != 0 ? theme().outlined(bg, stroke, 1, 8) : theme().rounded(bg, 8));
		c.setPadding(dp(10), dp(4), dp(10), dp(5));
		return c;
	}

	/** "Golden Sun (Spanish)" is shown as "Golden Sun" with a "Spanish" chip; titles without a suffix as they are. */
	private static String[] splitTitle(String title) {
		int open = title.lastIndexOf(" (");
		if (open > 0 && title.endsWith(")")) {
			return new String[] { title.substring(0, open), title.substring(open + 2, title.length() - 1) };
		}
		return new String[] { title, null };
	}

	private String optionalNote(DescoreGameConfig.Unit unit) {
		List<String> optional = presentOptional(unit);
		return optional.isEmpty() ? null
				: optional.size() + " of " + unit.optionalFiles.length + " optional files found";
	}

	private void showDataPicker(String errorMessage) {
		screen = SCREEN_PICKER;
		screenMessage = errorMessage;
		DescoreTheme t = theme();
		LinearLayout col = newScreen(560, true);
		LinearLayout card = new LinearLayout(this);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setBackground(t.rounded(t.surfaceContainer, 28));
		card.setPadding(dp(24), dp(24), dp(24), dp(24));
		col.addView(card);
		addText(card, getConfig().title, t.primary, 14, 0).setTypeface(DescoreTheme.medium());
		addTitle(card, getConfig().title + " game data needed", t.onSurface, 24, 8);
		addText(card, getConfig().pickerHint, t.onSurfaceVariant, 15, 12);
		if (errorMessage != null) addBanner(card, errorMessage, true);
		LinearLayout choose = makeButton(getConfig().pickSingleFile ? "Choose File" : "Choose Folder",
				DescoreWidgets.Icon.ADD, BUTTON_FILLED, new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						openFolderPicker();
					}
				});
		choose.setTag("choose");
		LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		bp.topMargin = dp(24);
		card.addView(choose, bp);
		choose.requestFocus();
	}

	private void showCopyingProgress() {
		screen = SCREEN_COPYING;
		screenMessage = null;
		DescoreTheme t = theme();
		LinearLayout col = newScreen(560, true);
		col.setGravity(Gravity.CENTER_HORIZONTAL);
		ProgressBar spinner = new ProgressBar(this);
		spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(t.primary));
		col.addView(spinner, new LinearLayout.LayoutParams(dp(48), dp(48)));
		addTitle(col, "Copying game data...", t.onSurface, 18, 20).setGravity(Gravity.CENTER);
	}

	private void showUnitSelect(String statusMessage) {
		screen = SCREEN_SELECT;
		screenMessage = statusMessage;
		final DescoreTheme t = theme();
		int w = widthDp();
		LinearLayout col = newScreen(1040, false);

		addTitle(col, getConfig().title, t.onSurface, w >= 600 ? 32 : 26, w >= 600 ? 16 : 8);
		addText(col, "Choose a game to play", t.onSurfaceVariant, 15, 4);
		if (statusMessage != null) addBanner(col, statusMessage, false);

		// The last game played (or the only one ready) gets the big card with the Play button.
		final android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
		DescoreGameConfig.Unit hero = null;
		int last = prefs.getInt(KEY_LAST_UNIT, -1);
		List<DescoreGameConfig.Unit> ready = new ArrayList<>();
		for (DescoreGameConfig.Unit u : getConfig().units) {
			if (isUnitReady(u)) ready.add(u);
		}
		for (DescoreGameConfig.Unit u : ready) {
			if (u.id == last) hero = u;
		}
		if (hero == null && ready.size() == 1) hero = ready.get(0);
		View firstFocus = null;
		if (hero != null) {
			firstFocus = addHero(col, hero, hero.id == last);
		}

		// Every other game: ready ones as cards that play on a tap, missing ones dimmed with what is missing.
		List<DescoreGameConfig.Unit> others = new ArrayList<>();
		for (DescoreGameConfig.Unit u : getConfig().units) {
			if (u == hero) continue;
			if (isUnitReady(u) || !u.hideWhenMissing) others.add(u);
		}
		if (!others.isEmpty()) {
			addSectionHeader(col, hero != null ? "All games" : "Games");
			int columns = w >= 900 ? 3 : w >= 560 ? 2 : 1;
			LinearLayout row = null;
			for (int i = 0; i < others.size(); i++) {
				if (i % columns == 0) {
					row = new LinearLayout(this);
					row.setOrientation(LinearLayout.HORIZONTAL);
					LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
							ViewGroup.LayoutParams.WRAP_CONTENT);
					if (i > 0) rp.topMargin = dp(12);
					col.addView(row, rp);
				}
				View card = unitCard(others.get(i));
				LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
				if (i % columns > 0) cp.leftMargin = dp(12);
				row.addView(card, cp);
				if (firstFocus == null && card.isFocusable()) firstFocus = card;
			}
			// Keep the last row's cards the same width as the others.
			int rest = others.size() % columns;
			if (rest > 0) {
				for (int i = rest; i < columns; i++) {
					LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, 0, 1f);
					cp.leftMargin = dp(12);
					row.addView(new View(this), cp);
				}
			}
		}

		addSectionHeader(col, "Game data");
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setBackground(t.rounded(t.surfaceContainer, 24));
		box.setPadding(dp(4), dp(4), dp(4), dp(4));
		col.addView(box);
		LinearLayout addMore = makeButton("Add More Game Data", DescoreWidgets.Icon.ADD, BUTTON_OUTLINED,
				new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						openFolderPicker();
					}
				});
		addMore.setTag("add");
		LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		ap.setMargins(dp(12), dp(12), dp(12), dp(12));
		box.addView(addMore, ap);
		if (firstFocus == null) firstFocus = addMore;

		if (getConfig().units.size() > 1) {
			// Skip this screen next time and go straight into the last game played (a game's menu can bring
			// it back).
			final LinearLayout auto = new LinearLayout(this);
			auto.setOrientation(LinearLayout.HORIZONTAL);
			auto.setGravity(Gravity.CENTER_VERTICAL);
			auto.setFocusable(true);
			auto.setClickable(true);
			noFocusHighlight(auto);
			auto.setTag("auto");
			auto.setMinimumHeight(dp(64));
			auto.setPadding(dp(16), dp(8), dp(16), dp(8));
			auto.setBackground(t.focusable(android.graphics.Color.TRANSPARENT, t.secondaryContainer, 20));
			LinearLayout texts = new LinearLayout(this);
			texts.setOrientation(LinearLayout.VERTICAL);
			addText(texts, "Start my last game straight away", t.onSurface, 16, 0);
			addText(texts, "Skips this screen. \"Switch game\" in the game's menu brings it back.",
					t.onSurfaceVariant, 13, 2);
			auto.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
			final DescoreWidgets.Switch sw = new DescoreWidgets.Switch(this, t);
			sw.setChecked(prefs.getBoolean(KEY_AUTO_LAST, false), false);
			LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			sp.leftMargin = dp(16);
			auto.addView(sw, sp);
			auto.setOnClickListener(new View.OnClickListener() {
				@Override public void onClick(View v) {
					boolean on = !sw.isChecked();
					sw.setChecked(on, true);
					prefs.edit().putBoolean(KEY_AUTO_LAST, on).apply();
				}
			});
			box.addView(auto, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
		}
		firstFocus.requestFocus();
	}

	private void addSectionHeader(LinearLayout col, String text) {
		TextView h = addTitle(col, text, theme().primary, 14, 0);
		h.setPadding(dp(4), dp(28), dp(4), dp(10));
	}

	/** The big card: the game's name, its language and a large Play button. Returns the button. */
	private View addHero(LinearLayout col, final DescoreGameConfig.Unit unit, boolean lastPlayed) {
		DescoreTheme t = theme();
		boolean wide = widthDp() >= 600;
		LinearLayout card = new LinearLayout(this);
		card.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
		card.setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.NO_GRAVITY);
		card.setBackground(t.rounded(t.primaryContainer, 28));
		card.setPadding(dp(24), dp(24), dp(24), dp(24));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(24);
		col.addView(card, lp);

		LinearLayout texts = new LinearLayout(this);
		texts.setOrientation(LinearLayout.VERTICAL);
		card.addView(texts, wide ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
				: new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		addTitle(texts, lastPlayed ? "Continue playing" : "Ready to play",
				DescoreTheme.alpha(t.onPrimaryContainer, 0.8f), 14, 0);
		String[] parts = splitTitle(unit.title);
		addTitle(texts, parts[0], t.onPrimaryContainer, wide ? 30 : 26, 4);
		LinearLayout chips = new LinearLayout(this);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		chips.setPadding(0, dp(12), 0, 0);
		if (parts[1] != null) {
			chips.addView(chip(parts[1], t.onPrimaryContainer, android.graphics.Color.TRANSPARENT,
					DescoreTheme.alpha(t.onPrimaryContainer, 0.5f)));
		}
		String note = optionalNote(unit);
		if (note != null) {
			TextView n = chip(note, t.onPrimaryContainer, android.graphics.Color.TRANSPARENT,
					DescoreTheme.alpha(t.onPrimaryContainer, 0.5f));
			LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			if (parts[1] != null) np.leftMargin = dp(8);
			chips.addView(n, np);
		}
		if (chips.getChildCount() > 0) texts.addView(chips);

		LinearLayout play = makeButton("Play", DescoreWidgets.Icon.PLAY, BUTTON_FILLED, new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				play(unit);
			}
		});
		play.setTag("hero");
		play.setMinimumWidth(dp(160));
		play.setMinimumHeight(dp(64));
		LinearLayout.LayoutParams pp = wide
				? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
				: new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		if (wide) pp.leftMargin = dp(24); else pp.topMargin = dp(24);
		card.addView(play, pp);
		return play;
	}

	/** A game's card: ready ones play on a tap (or A), missing ones say which files they still need. */
	private View unitCard(final DescoreGameConfig.Unit unit) {
		DescoreTheme t = theme();
		boolean ready = isUnitReady(unit);
		LinearLayout card = new LinearLayout(this);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setPadding(dp(20), dp(18), dp(20), dp(16));
		card.setTag("unit-" + unit.id);
		String[] parts = splitTitle(unit.title);
		addTitle(card, parts[0], t.onSurface, 18, 0);

		LinearLayout chips = new LinearLayout(this);
		chips.setOrientation(LinearLayout.HORIZONTAL);
		chips.setGravity(Gravity.CENTER_VERTICAL);
		chips.setPadding(0, dp(10), 0, 0);
		if (parts[1] != null) {
			TextView c = chip(parts[1], t.onSurfaceVariant, android.graphics.Color.TRANSPARENT, t.outlineVariant);
			LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			cp.rightMargin = dp(8);
			chips.addView(c, cp);
		}
		chips.addView(ready ? chip("Ready", t.onSecondaryContainer, t.secondaryContainer, 0)
				: chip("Missing", t.onErrorContainer, t.errorContainer, 0));
		card.addView(chips);

		String status = ready ? optionalNote(unit) : "Needs " + join(missingFiles(unit));
		if (status != null) addText(card, status, t.onSurfaceVariant, 13, 10);

		if (ready) {
			// Pushes the "Play" label to the bottom when a neighbour card in the row is taller.
			View spacer = new View(this);
			card.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));
			LinearLayout playLine = new LinearLayout(this);
			playLine.setOrientation(LinearLayout.HORIZONTAL);
			playLine.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
			playLine.setPadding(0, dp(12), 0, 0);
			playLine.addView(new DescoreWidgets.Icon(this, t, DescoreWidgets.Icon.PLAY, 18, t.primary));
			TextView pl = new TextView(this);
			pl.setText("Play");
			pl.setTextColor(t.primary);
			pl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
			pl.setTypeface(DescoreTheme.medium());
			pl.setPadding(dp(6), 0, 0, 0);
			playLine.addView(pl);
			card.addView(playLine, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			card.setFocusable(true);
			card.setClickable(true);
			noFocusHighlight(card);
			card.setBackground(t.focusRing(t.surfaceContainer, t.primary, 24));
			card.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					play(unit);
				}
			});
		} else {
			card.setBackground(t.rounded(t.surfaceContainerLow, 24));
			card.setAlpha(0.75f);
		}
		return card;
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		// Turned, folded or unfolded (the activity is not recreated): lay the same screen out for the new size.
		t = null; // the density can change with the screen
		View focused = getCurrentFocus();
		Object tag = focused != null ? focused.getTag() : null;
		switch (screen) {
			case SCREEN_PICKER:
				showDataPicker(screenMessage);
				break;
			case SCREEN_COPYING:
				showCopyingProgress();
				break;
			case SCREEN_SELECT:
				showUnitSelect(screenMessage);
				break;
			default:
				break;
		}
		if (tag != null) {
			View again = getWindow().getDecorView().findViewWithTag(tag);
			if (again != null) again.requestFocus();
		}
		setImmersive();
	}

	private static String join(List<String> values) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(values.get(i));
		}
		return sb.toString();
	}

	// --- Folder pick + copy ---------------------------------------------------------------------

	private void openFolderPicker() {
		if (Build.VERSION.SDK_INT < 21) {
			showDataPicker("This Android version can't select external files.");
			return;
		}
		if (getConfig().pickSingleFile) {
			Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
			pick.addCategory(Intent.CATEGORY_OPENABLE);
			pick.setType("*/*"); // ROM dumps have no registered MIME type
			startActivityForResult(pick, REQUEST_CODE_OPEN_DATA_FOLDER);
			return;
		}
		startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
				REQUEST_CODE_OPEN_DATA_FOLDER);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == REQUEST_CODE_OPEN_DATA_FOLDER) {
			if (resultCode != RESULT_OK || data == null || data.getData() == null) {
				return; // cancelled - leave whichever screen was already showing
			}
			copyDataFromTree(data.getData(), getConfig().pickSingleFile);
		}
	}

	private void copyDataFromTree(final Uri treeUri, final boolean singleFile) {
		showCopyingProgress();
		new Thread(new Runnable() {
			@Override
			public void run() {
				final String result = singleFile ? doCopySingleFile(treeUri) : doCopyData(treeUri);
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						if (anyUnitReady()) {
							showUnitSelect(result);
						} else {
							showDataPicker(result != null ? result
									: "Didn't find any of the game's files in that folder.");
						}
					}
				});
			}
		}).start();
	}

	/** Background thread: scan the picked tree once, then copy every file any unit wants. */
	private String doCopyData(Uri treeUri) {
		Map<String, Uri> found = new HashMap<>();
		try {
			int[] budget = {MAX_SCAN_ENTRIES};
			scanTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri), 0, found, budget);
		} catch (IOException e) {
			Log.i(TAG, "doCopyData: failed to list picked folder: " + e.getMessage());
			return "Couldn't read that folder: " + e.getMessage();
		}
		Log.i(TAG, "doCopyData: scan found " + found.size() + " distinct file names");
		return copyFound(found);
	}

	/** Single-file mode: the picked document is the whole "tree". */
	private String doCopySingleFile(Uri docUri) {
		Map<String, Uri> found = new HashMap<>();
		Cursor cursor = getContentResolver().query(docUri,
				new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
		String name = null;
		if (cursor != null) {
			try {
				if (cursor.moveToFirst()) name = cursor.getString(0);
			} finally {
				cursor.close();
			}
		}
		if (name == null) name = docUri.getLastPathSegment();
		if (name == null) return "Couldn't read that file.";
		found.put(name.toLowerCase(Locale.US), docUri);
		return copyFound(found);
	}

	/** Copies every found file any unit wants (aliased files go under their stored name). */
	private String copyFound(Map<String, Uri> found) {
		//noinspection ResultOfMethodCallIgnored
		rootDir.mkdirs();
		List<String> summary = new ArrayList<>();
		for (DescoreGameConfig.Unit unit : getConfig().units) {
			File dir = unitDir(unit);
			int copied = 0;
			boolean sawAny = false;
			List<String> aliasesDone = new ArrayList<>();
			for (Map.Entry<String, Uri> entry : found.entrySet()) {
				String lowerName = entry.getKey();
				if (!unit.wants(lowerName)) continue;
				String storeName = unit.aliasFor(lowerName);
				if (storeName == null) {
					storeName = lowerName;
				} else if (aliasesDone.contains(storeName)) {
					continue; // first file with that extension wins
				} else if (!aliasCheckPasses(unit, entry.getValue())) {
					continue; // another unit's file with the same extension
				}
				sawAny = true;
				//noinspection ResultOfMethodCallIgnored
				dir.mkdirs();
				if (copyOneFile(entry.getValue(), lowerName, new File(dir, storeName))) {
					copied++;
					aliasesDone.add(storeName);
				}
			}
			if (sawAny) {
				summary.add(copied + " file" + (copied == 1 ? "" : "s") + " for " + unit.title);
			}
		}
		if (summary.isEmpty()) return null;
		return "Copied " + join(summary) + ".";
	}

	/** True when the unit has no content check, or the file has the expected bytes at the expected offset. */
	private boolean aliasCheckPasses(DescoreGameConfig.Unit unit, Uri uri) {
		if (unit.aliasCheckBytes == null || unit.aliasCheckOffset < 0) return true;
		byte[] want;
		try {
			want = unit.aliasCheckBytes.getBytes("US-ASCII");
		} catch (java.io.UnsupportedEncodingException e) {
			return false;
		}
		try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
			if (in == null) return false;
			long skip = unit.aliasCheckOffset;
			while (skip > 0) {
				long n = in.skip(skip);
				if (n <= 0) {
					if (in.read() < 0) return false;
					n = 1;
				}
				skip -= n;
			}
			byte[] got = new byte[want.length];
			int off = 0;
			while (off < got.length) {
				int n = in.read(got, off, got.length - off);
				if (n < 0) return false;
				off += n;
			}
			return java.util.Arrays.equals(got, want);
		} catch (IOException | SecurityException e) {
			return false;
		}
	}

	/**
	 * Recursively lists the picked tree into {@code out}, keyed by lower-cased display name. The
	 * first file found with a given name wins, so a clean top-level copy beats a stray duplicate
	 * buried in a backup folder that happens to be visited later.
	 */
	private void scanTree(Uri treeUri, String parentDocId, int depth, Map<String, Uri> out,
			int[] budget) throws IOException {
		Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
		Cursor cursor = getContentResolver().query(childrenUri, new String[]{
				DocumentsContract.Document.COLUMN_DOCUMENT_ID,
				DocumentsContract.Document.COLUMN_DISPLAY_NAME,
				DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
		if (cursor == null) throw new IOException("folder query returned nothing");
		List<String> subDirs = new ArrayList<>();
		try {
			while (cursor.moveToNext() && budget[0] > 0) {
				budget[0]--;
				String docId = cursor.getString(0);
				String name = cursor.getString(1);
				String mime = cursor.getString(2);
				if (name == null) continue;
				if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
					subDirs.add(docId);
				} else {
					String key = name.toLowerCase(Locale.US);
					if (!out.containsKey(key)) {
						out.put(key, DocumentsContract.buildDocumentUriUsingTree(treeUri, docId));
					}
				}
			}
		} finally {
			cursor.close();
		}
		if (depth < MAX_SCAN_DEPTH) {
			for (String dirId : subDirs) {
				if (budget[0] <= 0) break;
				scanTree(treeUri, dirId, depth + 1, out, budget);
			}
		}
	}

	/**
	 * Copies one file to a temp name, verifies the byte count against the provider's reported size
	 * (an interrupted SAF read can otherwise silently leave a short file that an existence-only
	 * check would accept), then renames into place. Logs and returns false rather than throwing so
	 * one bad file can't abort the rest.
	 */
	private boolean copyOneFile(Uri sourceUri, String displayName, File destination) {
		File temp = new File(destination.getParentFile(), destination.getName() + ".tmp");
		InputStream in = null;
		OutputStream out = null;
		try {
			long expectedSize = queryDocumentSize(sourceUri);
			in = getContentResolver().openInputStream(sourceUri);
			if (in == null) {
				Log.i(TAG, "copyOneFile: could not open " + displayName);
				return false;
			}
			out = new FileOutputStream(temp);
			byte[] buffer = new byte[64 * 1024];
			long total = 0;
			int read;
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
				total += read;
			}
			out.flush();
			out.close();
			out = null;
			in.close();
			in = null;
			if (expectedSize >= 0 && total != expectedSize) {
				Log.i(TAG, "copyOneFile: " + displayName + " incomplete, got " + total + " of "
						+ expectedSize + " bytes");
				//noinspection ResultOfMethodCallIgnored
				temp.delete();
				return false;
			}
			//noinspection ResultOfMethodCallIgnored
			destination.delete();
			if (!temp.renameTo(destination)) {
				Log.i(TAG, "copyOneFile: renameTo(" + destination + ") failed");
				return false;
			}
			return true;
		} catch (IOException e) {
			Log.i(TAG, "copyOneFile: " + displayName + ": " + e.getMessage());
			//noinspection ResultOfMethodCallIgnored
			temp.delete();
			return false;
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (IOException ignored) {
				}
			}
			if (out != null) {
				try {
					out.close();
				} catch (IOException ignored) {
				}
			}
		}
	}

	private long queryDocumentSize(Uri documentUri) {
		Cursor cursor = getContentResolver().query(documentUri,
				new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
		if (cursor == null) return -1;
		try {
			if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
			return -1;
		} finally {
			cursor.close();
		}
	}

	// --- Lifecycle / misc --------------------------------------------------------------------

	@Override
	protected void onResume() {
		super.onResume();
		setImmersive();
	}

	private void setImmersive() {
		if (Build.VERSION.SDK_INT >= 19) {
			getWindow().getDecorView().setSystemUiVisibility(
					View.SYSTEM_UI_FLAG_LAYOUT_STABLE
							| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
							| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
							| View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
							| View.SYSTEM_UI_FLAG_FULLSCREEN
							| View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
		}
	}
}
