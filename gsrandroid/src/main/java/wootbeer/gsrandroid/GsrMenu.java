package wootbeer.gsrandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Process;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import wootbeer.descore.DescoreTheme;
import wootbeer.descore.DescoreWidgets;

/**
 * The in-game settings menu, in Material You colours (DescoreTheme), over the running game (the game is paused
 * while it is open, but keeps drawing, dimmed, so display changes show at once). Opened with Back, the Menu button
 * on a gamepad, or the on-screen MENU button.
 *
 * Its shape follows the screen it opens on, worked out each time it opens:
 * <ul>
 * <li>a tablet-sized screen (a foldable's inner screen): a two-pane panel, categories on the left and the chosen
 * category's settings on the right;</li>
 * <li>a phone-sized screen held upright (the cover screen): a bottom sheet over the lower part of the screen;</li>
 * <li>a phone-sized screen held sideways: a side panel on the right.</li>
 * </ul>
 *
 * It is made of ordinary Android views, so it works with touch and with a gamepad (d-pad moves, A selects, left and
 * right change a value, B or Back goes back) and a controller's A and B keep that meaning here no matter how the
 * gamepad buttons are mapped inside the game. Every change takes effect immediately and is saved by the native side.
 */
final class GsrMenu {
	private static final String[] VIEW = { "Native 240x160", "Expanded 360x240", "Fit screen" };
	private static final String[] ZOOM = { "Farthest", "1", "2", "3", "4", "5", "6", "7", "8", "9", "Closest" };
	private static final String[] ROOM_CLIP = { "Off", "Checked rooms", "Every room" };
	private static final String[] SCALE = { "Fit", "Whole pixels", "Stretch", "Zoom to fill" };
	private static final String[] FLICKER = { "Off", "Light", "Medium", "Strong" };
	private static final String[] FILTER = { "Sharp", "Smooth" };
	private static final String[] SCREEN = { "Raw", "Unlit", "Frontlit", "Backlit", "Classic", "Handheld",
			"Handheld (lighter)", "Soft", "Natural", "Warm", "Deep", "David's OG GBA" };
	/** The stored values of SCREEN (ScreenKind; 11, Custom, needs sliders and is not offered). */
	private static final int[] SCREEN_VALUES = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12 };
	private static final String[] OFF_ON = { "Off", "On" };
	private static final String[] HOLD_TOGGLE = { "Hold", "Toggle" };
	private static final int[] FF_VALUES = { 15, 20, 30, 40, 60, 80, 100, 160 };
	private static final String[] FF_LABELS = { "1.5x", "2x", "3x", "4x", "6x", "8x", "10x", "16x" };
	private static final String[] CHEAT_STEPS = { "0x", "0.5x", "1x", "2x", "5x", "10x", "25x", "50x", "100x" };
	private static final String[] CHEAT_DROP_STEPS = { "0x", "0.5x", "1x", "2x", "5x", "10x" };
	private static final String[] TOUCH_SIZE = { "0.90x", "0.95x", "1.00x", "1.10x", "1.20x", "1.35x", "1.50x", "1.65x", "1.80x" };

	/** Pages. The single-pane menu has P_MAIN (every category) and the sub-pages 1 to 5; the two-pane menu has the
	 *  categories (10 to 14) and the sub-pages, all side by side in its navigation list (About is the credits). */
	private static final int P_MAIN = 0, P_BUTTONS = 1, P_CREDITS = 2, P_CHEATS = 3, P_STATES = 4, P_SAVES = 5,
			P_DISPLAY = 10, P_SPEED = 11, P_AUDIO = 12, P_CONTROLS = 13, P_GAME = 14;
	/** The two-pane navigation list; -1 is a divider. */
	private static final int[] NAV_PAGES = { P_DISPLAY, P_SPEED, P_AUDIO, P_CONTROLS, P_GAME, P_CREDITS, -1,
			P_STATES, P_SAVES, P_CHEATS, P_BUTTONS };
	private static final String[] NAV_LABELS = { "Display", "Speed", "Audio", "Controls", "Game", "About", null,
			"Save states", "Saves", "Cheats", "Gamepad buttons" };

	private static final int LAYOUT_TWO_PANE = 0, LAYOUT_SHEET = 1, LAYOUT_SIDE = 2;

	/** Row kinds: what sits on the right of the label. */
	private static final int K_CHIP = 0, K_SWITCH = 1, K_SLIDER = 2, K_NAV = 3, K_ACTION = 4, K_TEXT = 5, K_LINK = 6;

	private final Activity activity;
	private final float density;
	private DescoreTheme t;
	private Dialog dialog;
	private LinearLayout list;
	/** The card rows are being added to while a page is built (null: the next row starts one). */
	private LinearLayout card;
	private int cardColor;
	private TextView title, subtitle;
	private View backButton;
	private ScrollView scroll;
	private boolean open, twoPane, building;
	private int page;
	/** Two-pane: the page shown in the content pane last time (kept between openings). */
	private int category = P_DISPLAY;
	/** The category the rows being built belong to (to reopen on the same place after a screen change). */
	private int buildCat = P_DISPLAY;
	private final List<Row> rows = new ArrayList<>();
	private final List<NavItem> navItems = new ArrayList<>();
	/** Single pane: the row that opened the sub-page, focused again on the way back. */
	private String returnKey;
	/** Set while the menu closes to reopen laid out for a new screen shape: where to come back to. */
	private int reopenPage = -1;
	private String reopenKey;

	GsrMenu(Activity activity) {
		this.activity = activity;
		this.density = activity.getResources().getDisplayMetrics().density;
	}

	// ---------------------------------------------------------------------------------------------------------------

	void show() {
		if (open) return;
		open = true;
		GsrNative.setMenuOpen(true);
		t = DescoreTheme.get(activity);

		Configuration c = activity.getResources().getConfiguration();
		int layout = c.smallestScreenWidthDp >= 600 ? LAYOUT_TWO_PANE
				: c.orientation == Configuration.ORIENTATION_PORTRAIT ? LAYOUT_SHEET : LAYOUT_SIDE;
		twoPane = layout == LAYOUT_TWO_PANE;

		dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
		Window w = dialog.getWindow();
		w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
		w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
		w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

		// The game shows, dimmed, around the panel; tapping it closes the menu.
		FrameLayout root = new FrameLayout(activity);
		root.setBackgroundColor(t.scrim);
		root.setOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View v) { close(); }
		});
		// A clickable view is focusable by default, and Android (API 26+) outlines a focused view with a thin
		// "default focus highlight" (the 1 px coloured frame around the visible game area). Same fix as DescoreView.
		root.setFocusable(false);
		noFocusHighlight(root);

		DescoreWidgets.Column panel = new DescoreWidgets.Column(activity);
		panel.setClickable(true); // taps on the panel's background do not reach the scrim
		panel.setFocusable(false);
		noFocusHighlight(panel);
		navItems.clear();
		if (layout == LAYOUT_TWO_PANE) {
			cardColor = t.surfaceContainer;
			panel.setOrientation(LinearLayout.HORIZONTAL);
			panel.setBackground(t.rounded(t.surfaceContainerLow, 28));
			panel.setPadding(dp(12), dp(12), dp(12), dp(12));
			panel.setMaxSize(dp(1080), dp(900));
			panel.addView(navPane(), new LinearLayout.LayoutParams(dp(c.screenWidthDp >= 720 ? 248 : 208),
					ViewGroup.LayoutParams.MATCH_PARENT));
			LinearLayout content = contentPane();
			content.setBackground(t.rounded(t.surface, 24));
			panel.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
			FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
			lp.setMargins(dp(24), dp(24), dp(24), dp(24));
			root.addView(panel, lp);
		} else {
			cardColor = t.surfaceContainerHigh;
			boolean sheet = layout == LAYOUT_SHEET;
			panel.setOrientation(LinearLayout.VERTICAL);
			panel.setBackground(sheet ? t.rounded(t.surfaceContainerLow, 28, 28, 0, 0)
					: t.rounded(t.surfaceContainerLow, 28, 0, 0, 28));
			if (sheet) {
				panel.setMaxSize(dp(640), 0);
				View handle = new View(activity);
				handle.setBackground(t.rounded(DescoreTheme.alpha(t.onSurfaceVariant, 0.4f), 2));
				LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(32), dp(4));
				hp.gravity = Gravity.CENTER_HORIZONTAL;
				hp.topMargin = dp(12);
				hp.bottomMargin = dp(2);
				panel.addView(handle, hp);
			} else {
				panel.setPadding(0, dp(8), 0, 0);
			}
			panel.addView(contentPane(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

			// The game stays in view above (cover screen upright) or to the left (sideways) of the panel.
			LinearLayout split = new LinearLayout(activity);
			split.setOrientation(sheet ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
			View gap = new View(activity); // not clickable: a tap on it reaches the scrim, which closes the menu
			if (sheet) {
				split.addView(gap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.30f));
				LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.70f);
				pp.gravity = Gravity.CENTER_HORIZONTAL;
				split.addView(panel, pp);
			} else {
				split.addView(gap, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.52f));
				split.addView(panel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.48f));
			}
			root.addView(split, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT));
		}

		dialog.setContentView(root);
		dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
			@Override
			public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
				if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
					// On the press, not the release: the release of the Back press that OPENED the menu arrives
					// here as well, and acting on it would close the menu again straight away.
					if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) goBack();
					return true;
				}
				// The buttons that open the menu (R3, Mode) also close it.
				if (keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR || keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
					// Close on the press, not the release: the release of the press that OPENED the menu lands here
					// too (the dialog has focus by then), and acting on it would close the menu straight away.
					if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) close();
					return true;
				}
				return false;
			}
		});
		dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
			@Override public void onDismiss(DialogInterface d) {
				open = false;
				GsrNative.setMenuOpen(false);
			}
		});
		dialog.show();
		dialog.getWindow().getDecorView().setSystemUiVisibility(
				View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
						| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
						| View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

		int target = reopenPage;
		String key = reopenKey;
		reopenPage = -1;
		reopenKey = null;
		returnKey = null;
		if (twoPane) {
			render(target > 0 ? target : category, false);
			if (key == null || !focusKey(key)) focusNav();
		} else {
			// The sub-pages exist in both shapes; a two-pane category's rows are all on the main page.
			render(target >= P_BUTTONS && target <= P_SAVES ? target : P_MAIN, false);
			if (key == null || !focusKey(key)) focusFirst();
		}
	}

	/** The screen folded, unfolded or turned while the menu may be open: lay it out again for the new shape. */
	void screenChanged() {
		if (!open) return;
		reopen(300);
	}

	/** Close, and open again laid out for the screen's shape after {@code delayMs}, on the same page and row. */
	private void reopen(long delayMs) {
		reopenPage = page;
		reopenKey = focusedKey();
		Row r = focusedRow();
		if (!twoPane && page == P_MAIN && r != null) reopenPage = r.cat;
		close();
		activity.getWindow().getDecorView().postDelayed(new Runnable() {
			@Override public void run() { show(); }
		}, delayMs);
	}

	private void close() {
		if (dialog != null) dialog.dismiss();
	}

	private void goBack() {
		if (twoPane) {
			// From the settings back to the categories; from the categories, close.
			if (list.hasFocus()) focusNav(); else close();
		} else if (page != P_MAIN) {
			showMain();
		} else {
			close();
		}
	}

	// ---- Panel parts ---------------------------------------------------------------------------------------------

	private static void noFocusHighlight(View v) {
		if (android.os.Build.VERSION.SDK_INT >= 26) v.setDefaultFocusHighlightEnabled(false);
	}

	private int dp(float v) { return (int) (v * density + 0.5f); }

	private TextView text(CharSequence s, int color, float sp, boolean medium) {
		TextView v = new TextView(activity);
		v.setText(s);
		v.setTextColor(color);
		v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		if (medium) v.setTypeface(DescoreTheme.medium());
		return v;
	}

	/** A round icon button for touch (header back / close). Not focusable: a gamepad has B and R3 for these. */
	private View iconButton(int icon, View.OnClickListener click) {
		DescoreWidgets.Icon b = new DescoreWidgets.Icon(activity, t, icon, 24, t.onSurfaceVariant);
		b.setPadding(dp(12), dp(12), dp(12), dp(12));
		b.setBackground(new RippleDrawable(ColorStateList.valueOf(DescoreTheme.alpha(t.onSurface, 0.12f)), null,
				t.rounded(0xFFFFFFFF, 24)));
		b.setClickable(true);
		b.setFocusable(false);
		noFocusHighlight(b);
		b.setOnClickListener(click);
		return b;
	}

	/** Header (title, supporting line, back and close), the scrolling list of the page, and the controls hint. */
	private LinearLayout contentPane() {
		LinearLayout col = new LinearLayout(activity);
		col.setOrientation(LinearLayout.VERTICAL);

		LinearLayout header = new LinearLayout(activity);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setPadding(dp(twoPane ? 12 : 8), dp(twoPane ? 12 : 4), dp(8), dp(4));
		backButton = iconButton(DescoreWidgets.Icon.BACK, new View.OnClickListener() {
			@Override public void onClick(View v) { goBack(); }
		});
		header.addView(backButton);
		LinearLayout titles = new LinearLayout(activity);
		titles.setOrientation(LinearLayout.VERTICAL);
		titles.setPadding(dp(8), dp(4), dp(8), dp(4));
		title = text("", t.onSurface, 22, true);
		subtitle = text("", t.onSurfaceVariant, 14, false);
		titles.addView(title);
		titles.addView(subtitle);
		header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		header.addView(iconButton(DescoreWidgets.Icon.CLOSE, new View.OnClickListener() {
			@Override public void onClick(View v) { close(); }
		}));
		col.addView(header);

		scroll = new ScrollView(activity);
		scroll.setFocusable(false);
		scroll.setVerticalScrollBarEnabled(false);
		scroll.setClipToPadding(false);
		noFocusHighlight(scroll);
		list = new LinearLayout(activity);
		list.setOrientation(LinearLayout.VERTICAL);
		list.setPadding(dp(twoPane ? 16 : 12), 0, dp(twoPane ? 16 : 12), dp(16));
		scroll.addView(list);
		col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		if (gamepadConnected()) {
			TextView hint = text(controlsHint(), t.onSurfaceVariant, 12, false);
			hint.setGravity(Gravity.CENTER);
			hint.setPadding(dp(16), dp(8), dp(16), dp(10));
			col.addView(divider());
			col.addView(hint);
		}
		return col;
	}

	private View divider() {
		View d = new View(activity);
		d.setBackgroundColor(t.outlineVariant);
		d.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
		return d;
	}

	/** "D-pad Move · Left/Right Change · ..." with the buttons in the stronger colour. */
	private CharSequence controlsHint() {
		String[][] parts = { { "D-pad", "move" }, { "Left/Right", "change" }, { "A", "select" }, { "B", "back" },
				{ "R3", "close" } };
		SpannableStringBuilder b = new SpannableStringBuilder();
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) b.append("   ·   ");
			int s = b.length();
			b.append(parts[i][0]);
			b.setSpan(new ForegroundColorSpan(t.onSurface), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			b.append(" ").append(parts[i][1]);
		}
		return b;
	}

	/** True when a gamepad (or joystick) is connected: only then is the controls hint worth its space. */
	private static boolean gamepadConnected() {
		for (int id : InputDevice.getDeviceIds()) {
			InputDevice d = InputDevice.getDevice(id);
			if (d == null || d.isVirtual()) continue;
			int s = d.getSources();
			if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
					|| (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
		}
		return false;
	}

	/** Two-pane: the categories, with the game's name over them. */
	private LinearLayout navPane() {
		LinearLayout col = new LinearLayout(activity);
		col.setOrientation(LinearLayout.VERTICAL);
		TextView head = text("Settings", t.onSurface, 22, true);
		head.setPadding(dp(16), dp(16), dp(12), 0);
		col.addView(head);
		TextView sub = text(gameTitle() + " · paused", t.onSurfaceVariant, 14, false);
		sub.setPadding(dp(16), dp(2), dp(12), dp(16));
		col.addView(sub);

		ScrollView navScroll = new ScrollView(activity);
		navScroll.setFocusable(false);
		navScroll.setVerticalScrollBarEnabled(false);
		noFocusHighlight(navScroll);
		LinearLayout navList = new LinearLayout(activity);
		navList.setOrientation(LinearLayout.VERTICAL);
		navList.setPadding(0, 0, dp(12), dp(8));
		for (int i = 0; i < NAV_PAGES.length; i++) {
			if (NAV_PAGES[i] < 0) {
				View d = divider();
				LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) d.getLayoutParams();
				lp.setMargins(dp(16), dp(8), dp(16), dp(8));
				navList.addView(d, lp);
				continue;
			}
			NavItem n = new NavItem(NAV_LABELS[i], NAV_PAGES[i]);
			navItems.add(n);
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			lp.bottomMargin = dp(2);
			navList.addView(n, lp);
		}
		navScroll.addView(navList);
		col.addView(navScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		return col;
	}

	/** An entry of the two-pane navigation list. Focusing it shows its page; A, a tap or Right goes into it. */
	private final class NavItem extends TextView {
		final int target;

		NavItem(String label, int target) {
			super(activity);
			this.target = target;
			setText(label);
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
			setTypeface(DescoreTheme.medium());
			setGravity(Gravity.CENTER_VERTICAL);
			setMinHeight(dp(52));
			setPadding(dp(20), dp(6), dp(16), dp(6));
			setFocusable(true);
			setClickable(true);
			noFocusHighlight(this);
			StateListDrawable s = new StateListDrawable();
			s.addState(new int[] { android.R.attr.state_focused, android.R.attr.state_selected },
					t.outlined(t.secondaryContainer, t.primary, 2, 26));
			s.addState(new int[] { android.R.attr.state_focused },
					t.outlined(DescoreTheme.alpha(t.onSurface, 0.08f), t.primary, 2, 26));
			s.addState(new int[] { android.R.attr.state_selected }, t.rounded(t.secondaryContainer, 26));
			s.addState(new int[0], t.rounded(Color.TRANSPARENT, 26));
			setBackground(new RippleDrawable(ColorStateList.valueOf(DescoreTheme.alpha(t.onSurface, 0.12f)), s,
					t.rounded(0xFFFFFFFF, 26)));
			setTextColor(new ColorStateList(
					new int[][] { { android.R.attr.state_selected }, { android.R.attr.state_focused }, {} },
					new int[] { t.onSecondaryContainer, t.onSurface, t.onSurfaceVariant }));
			setOnClickListener(new View.OnClickListener() {
				@Override public void onClick(View v) {
					if (page != NavItem.this.target) render(NavItem.this.target, true); else focusFirst();
				}
			});
		}

		@Override
		protected void onFocusChanged(boolean gained, int direction, Rect previous) {
			super.onFocusChanged(gained, direction, previous);
			// Moving through the list shows each page; the focus stays here until A or Right.
			if (gained && !building && open && page != target) render(target, false);
		}

		@Override
		public boolean onKeyDown(int keyCode, KeyEvent event) {
			if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { focusFirst(); return true; }
			if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return true;
			return super.onKeyDown(keyCode, event);
		}
	}

	private void focusNav() {
		for (NavItem n : navItems) {
			if (n.target == page) { n.requestFocus(); return; }
		}
		if (!navItems.isEmpty()) navItems.get(0).requestFocus();
	}

	// ---- Building pages ------------------------------------------------------------------------------------------

	/** Shows a page: clears the list, builds the page's rows, and focuses its first row if {@code focus}. */
	private void render(int p, boolean focus) {
		building = true;
		try {
			page = p;
			if (twoPane && p != P_MAIN) category = p;
			rows.clear();
			card = null;
			list.removeAllViews();
			scroll.scrollTo(0, 0);
			backButton.setVisibility(!twoPane && p != P_MAIN ? View.VISIBLE : View.GONE);
			switch (p) {
				case P_DISPLAY: head("Display", "Screen view, picture and effects"); buildDisplay(); break;
				case P_SPEED: head("Speed", "Fast-forward"); buildSpeed(); break;
				case P_AUDIO: head("Audio", "Volume and sound"); buildAudio(); break;
				case P_CONTROLS: head("Controls", "Gamepad and touch controls"); buildControls(); break;
				case P_GAME: head("Game", gameTitle()); buildGame(); break;
				case P_BUTTONS: head("Gamepad buttons", "The buttons the game uses"); buildButtons(); break;
				case P_CREDITS: head(twoPane ? "About" : "Credits", "Golden Sun Recompiled for Android"); buildCredits(); break;
				case P_CHEATS: head("Cheats", "Applied at once, for this play session only"); buildCheats(); break;
				case P_STATES: head("Save states", "Instant snapshots of the game"); buildStates(); break;
				case P_SAVES: head("Saves", "The game's own save"); buildSaves(); break;
				default:
					head("Settings", gameTitle() + " · paused");
					buildDisplay();
					buildSpeed();
					buildAudio();
					buildControls();
					buildGame();
					category("About", P_CREDITS);
					nav("Credits", P_CREDITS);
					break;
			}
			for (NavItem n : navItems) n.setSelected(n.target == p);
		} finally {
			building = false;
		}
		if (focus) focusFirst();
	}

	private void head(String heading, String supporting) {
		title.setText(heading);
		subtitle.setText(supporting);
		subtitle.setVisibility(supporting == null ? View.GONE : View.VISIBLE);
	}

	/** Builds the page again (a setting changed which rows there are, or what they show), keeping the place. */
	private void rebuild() {
		if (!open) return;
		final String key = focusedKey();
		final int y = scroll.getScrollY();
		render(page, false);
		scroll.post(new Runnable() {
			@Override public void run() {
				scroll.scrollTo(0, y);
				if (key != null && !focusKey(key)) focusFirst();
			}
		});
	}

	private void refreshAll() {
		for (Row r : rows) r.refresh();
	}

	private Row focusedRow() {
		View f = list == null ? null : list.findFocus();
		while (f != null && !(f instanceof Row)) {
			ViewParent p = f.getParent();
			f = p instanceof View ? (View) p : null;
		}
		return (Row) f;
	}

	private String focusedKey() {
		Row r = focusedRow();
		return r == null ? null : r.key;
	}

	private boolean focusKey(String key) {
		for (Row r : rows) {
			if (key.equals(r.key) && r.isFocusable()) return r.requestFocus();
		}
		return false;
	}

	private void focusFirst() {
		Row r = firstFocusable();
		if (r != null) r.requestFocus();
	}

	private Row firstFocusable() {
		for (Row r : rows) {
			if (r.isFocusable() && r.getVisibility() == View.VISIBLE) return r;
		}
		return null;
	}

	private Row lastFocusable() {
		for (int i = rows.size() - 1; i >= 0; i--) {
			Row r = rows.get(i);
			if (r.isFocusable() && r.getVisibility() == View.VISIBLE) return r;
		}
		return null;
	}

	/** Single pane: a category's heading on the main page. Two-pane: the page title says it already. */
	private void category(String name, int id) {
		buildCat = id;
		if (!twoPane) section(name);
	}

	/** A group of rows inside a category: its own card only in the roomier two-pane layout. */
	private void sub(String name) {
		if (twoPane) section(name);
	}

	/** A heading in the primary colour and a new card for the rows after it. */
	private void section(String text) {
		TextView h = text(text, t.primary, 14, true);
		h.setPadding(dp(16), dp(list.getChildCount() == 0 ? 8 : 20), dp(16), dp(8));
		list.addView(h);
		card = null;
	}

	/** The card rows go into: the open one, or a new one. */
	private LinearLayout target() {
		if (card == null) {
			card = new LinearLayout(activity);
			card.setOrientation(LinearLayout.VERTICAL);
			card.setBackground(t.rounded(cardColor, 24));
			card.setPadding(dp(4), dp(4), dp(4), dp(4));
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			if (list.getChildCount() > 0 && !(list.getChildAt(list.getChildCount() - 1) instanceof TextView)) {
				lp.topMargin = dp(12);
			}
			list.addView(card, lp);
		}
		return card;
	}

	/** Text inside the current card (credits, notes about a group of rows). */
	private TextView info(String s, int color, float sp) {
		TextView v = text(s, color, sp, false);
		v.setPadding(dp(16), dp(10), dp(16), dp(10));
		v.setLineSpacing(0, 1.1f);
		target().addView(v);
		return v;
	}

	/** Supporting text outside the cards. */
	private TextView note(String s) {
		TextView v = text(s, t.onSurfaceVariant, 13, false);
		v.setPadding(dp(16), dp(12), dp(16), dp(4));
		v.setLineSpacing(0, 1.1f);
		list.addView(v);
		card = null;
		return v;
	}

	// ---- Rows ----------------------------------------------------------------------------------------------------

	/**
	 * A row: a label (with an optional supporting line and "This screen" badge) and, on the right, the value: a
	 * chip with arrows, a switch, a slider, a chevron to a page, or nothing for an action. A click (tap or A) does
	 * {@code step(1)}; left and right on a d-pad step back and forth.
	 */
	private abstract class Row extends LinearLayout {
		final String key;
		final int kind, cat;
		private final TextView label, support;
		private TextView valueText, badge;
		private LinearLayout chip;
		private DescoreWidgets.Icon left, right, chevron;
		private DescoreWidgets.Switch sw;
		DescoreWidgets.Slider slider;
		private String fixedSupport;

		Row(String labelText, int kind) {
			super(activity);
			this.key = labelText;
			this.kind = kind;
			this.cat = buildCat;
			setOrientation(HORIZONTAL);
			setGravity(Gravity.CENTER_VERTICAL);
			setFocusable(true);
			setClickable(true);
			setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
			noFocusHighlight(this);
			setMinimumHeight(dp(56));
			setPadding(dp(16), dp(8), dp(kind == K_CHIP ? 8 : 16), dp(8));
			setBackground(t.focusable(Color.TRANSPARENT, t.secondaryContainer, 20));

			LinearLayout col = new LinearLayout(activity);
			col.setOrientation(VERTICAL);
			label = text(labelText, t.onSurface, 16, false);
			col.addView(label);
			support = text("", t.onSurfaceVariant, 13, false);
			support.setVisibility(GONE);
			col.addView(support);
			if (kind == K_SLIDER) {
				slider = new DescoreWidgets.Slider(activity, t);
				LayoutParams sp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
				sp.leftMargin = -dp(12); // the thumb's room: line the track up with the label
				col.addView(slider, sp);
			}
			addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

			LayoutParams tp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			tp.leftMargin = dp(12);
			switch (kind) {
				case K_CHIP: {
					chip = new LinearLayout(activity);
					chip.setOrientation(HORIZONTAL);
					chip.setGravity(Gravity.CENTER_VERTICAL);
					left = arrow(DescoreWidgets.Icon.CHEVRON_LEFT, -1);
					right = arrow(DescoreWidgets.Icon.CHEVRON_RIGHT, 1);
					valueText = text("", t.primary, 14, true);
					valueText.setGravity(Gravity.CENTER);
					valueText.setMaxWidth(dp(twoPane ? 220 : 150));
					valueText.setMaxLines(2);
					chip.addView(left);
					chip.addView(valueText);
					chip.addView(right);
					chip.setMinimumHeight(dp(40));
					addView(chip, tp);
					break;
				}
				case K_SWITCH:
					sw = new DescoreWidgets.Switch(activity, t);
					addView(sw, tp);
					break;
				case K_SLIDER:
				case K_TEXT:
					valueText = text("", kind == K_TEXT ? t.onSurfaceVariant : t.primary, 14, true);
					valueText.setGravity(Gravity.END);
					valueText.setMaxWidth(dp(140));
					addView(valueText, tp);
					break;
				case K_NAV:
				case K_LINK:
					chevron = new DescoreWidgets.Icon(activity, t,
							kind == K_NAV ? DescoreWidgets.Icon.CHEVRON_RIGHT : DescoreWidgets.Icon.OPEN, 24,
							t.onSurfaceVariant);
					addView(chevron, tp);
					break;
				default:
					break;
			}
			target().addView(this, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			rows.add(this);
			setOnClickListener(new View.OnClickListener() {
				@Override public void onClick(View v) { act(1, true); }
			});
			look(false);
		}

		/** The chip's arrows, for touch: a tap steps the value that way. */
		private DescoreWidgets.Icon arrow(int icon, final int dir) {
			DescoreWidgets.Icon a = new DescoreWidgets.Icon(activity, t, icon, 20, t.primary);
			a.setPadding(dp(6), dp(10), dp(6), dp(10));
			a.setClickable(true);
			a.setBackground(new RippleDrawable(ColorStateList.valueOf(DescoreTheme.alpha(t.onSurface, 0.12f)), null,
					t.rounded(0xFFFFFFFF, 20)));
			a.setOnClickListener(new View.OnClickListener() {
				@Override public void onClick(View v) { act(dir, true); }
			});
			return a;
		}

		/** The value shown on the right (chips, sliders, button names); also kept for switches. */
		abstract String value();
		abstract void step(int dir);

		/** A supporting line under the label, or null. */
		String support() { return fixedSupport; }
		boolean checked() { return false; }
		int sliderIndex() { return 0; }
		/** False at the ends of a slider, which does not wrap round under left and right. */
		boolean canStep(int dir) { return true; }

		Row supporting(String s) {
			fixedSupport = s;
			refresh();
			return this;
		}

		Row badge(String s) {
			if (badge == null) {
				badge = text(s, t.onSecondaryContainer, 11, true);
				badge.setBackground(t.rounded(t.secondaryContainer, 6));
				badge.setPadding(dp(6), dp(1), dp(6), dp(2));
				LayoutParams bp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
				bp.topMargin = dp(4);
				((LinearLayout) label.getParent()).addView(badge, 1, bp);
			}
			return this;
		}

		void refresh() {
			String v = value();
			if (valueText != null) valueText.setText(v == null ? "" : v);
			if (sw != null) sw.setChecked(checked(), sw.isChecked() != checked() && isShown() && getWidth() > 0);
			if (slider != null) slider.setIndex(sliderIndex());
			String s = support();
			support.setText(s == null ? "" : s);
			support.setVisibility(s == null || s.length() == 0 ? GONE : VISIBLE);
		}

		/** A change from a tap, A, or left / right; the other rows then show what it changed. */
		void act(int dir, boolean wrap) {
			if (!wrap && !canStep(dir)) return;
			step(dir);
			if (open) refreshAll();
		}

		private boolean changesValue() {
			return kind == K_CHIP || kind == K_SWITCH || kind == K_SLIDER;
		}

		@Override
		public boolean onKeyDown(int keyCode, KeyEvent event) {
			if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
				if (changesValue()) act(-1, false);
				else if (twoPane) focusNav();
				return true;
			}
			if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
				act(1, false);
				return true;
			}
			// Two-pane: up from the first row or down from the last would let focus search land on a category
			// on the left, which would switch the page. Stay (B goes back to the categories).
			if (twoPane && !rows.isEmpty()) {
				if (keyCode == KeyEvent.KEYCODE_DPAD_UP && this == firstFocusable()) {
					scroll.smoothScrollTo(0, 0);
					return true;
				}
				if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && this == lastFocusable()) return true;
			}
			return super.onKeyDown(keyCode, event);
		}

		@Override
		protected void onFocusChanged(boolean gained, int direction, Rect previous) {
			super.onFocusChanged(gained, direction, previous);
			look(gained);
		}

		/** Colours for the focused (secondaryContainer) or normal row. */
		private void look(boolean f) {
			label.setTextColor(f ? t.onSecondaryContainer : t.onSurface);
			support.setTextColor(f ? DescoreTheme.alpha(t.onSecondaryContainer, 0.8f) : t.onSurfaceVariant);
			int accent = f ? t.onSecondaryContainer : t.primary;
			if (valueText != null) valueText.setTextColor(kind == K_TEXT && !f ? t.onSurfaceVariant : accent);
			if (chip != null) {
				chip.setBackground(t.outlined(Color.TRANSPARENT, f ? DescoreTheme.alpha(t.onSecondaryContainer, 0.5f)
						: t.outlineVariant, 1, 12));
				left.setColor(accent);
				right.setColor(accent);
			}
			if (chevron != null) chevron.setColor(f ? t.onSecondaryContainer : t.onSurfaceVariant);
			if (slider != null) slider.setFocusedLook(f);
		}
	}

	private Row choice(String label, final int setting, final int[] values, final String[] labels, int kind) {
		final Row r = new Row(label, kind) {
			private int index() {
				int cur = GsrNative.getSetting(setting), best = 0;
				for (int i = 0; i < values.length; i++) {
					if (Math.abs(values[i] - cur) < Math.abs(values[best] - cur)) best = i;
				}
				return best;
			}
			@Override String value() { return labels[index()]; }
			@Override boolean checked() { return index() != 0; }
			@Override int sliderIndex() { return index(); }
			@Override boolean canStep(int dir) {
				if (kind != K_SLIDER) return true;
				int i = index() + dir;
				return i >= 0 && i < values.length;
			}
			@Override void step(int dir) {
				int i = (index() + dir + values.length) % values.length;
				set(i);
			}
			void set(int i) {
				GsrNative.setSetting(setting, values[i]);
				// Floating D-pad: the right side of the screen is for tapping, so turn the tap gestures on with it.
				if (setting == GsrNative.TOUCH_DPAD && values[i] == 2) {
					GsrNative.setSetting(GsrNative.TAP_A, 1);
					GsrNative.setSetting(GsrNative.TWO_FINGER_B, 1);
				}
				refresh();
				// Some rows are only there for one choice (Zoom for Fit screen, Turbo speed with the turbo buttons on, the
				// tap gestures' rows follow the direction control): build the page again.
				if (setting == GsrNative.VIEW_MODE || setting == GsrNative.TURBO || setting == GsrNative.TOUCH_DPAD) {
					list.post(new Runnable() {
						@Override public void run() { rebuild(); }
					});
				}
			}
		};
		if (r.slider != null) {
			r.slider.setCount(values.length);
			r.slider.setListener(new DescoreWidgets.Slider.Listener() {
				@Override public void onPick(int index) {
					GsrNative.setSetting(setting, values[index]);
					refreshAll();
				}
			});
		}
		if (GsrProfile.perScreen(setting)) r.badge("This screen");
		r.refresh();
		return r;
	}

	private Row choice(String label, int setting, int[] values, String[] labels) {
		return choice(label, setting, values, labels, labels == OFF_ON ? K_SWITCH : K_CHIP);
	}

	private Row choice(String label, int setting, String[] labels) {
		return choice(label, setting, indexes(labels.length), labels);
	}

	private static int[] indexes(int n) {
		int[] v = new int[n];
		for (int i = 0; i < n; i++) v[i] = i;
		return v;
	}

	/** An Off/On switch of the game's own options (upstream's F1 Game page), saved with the game. */
	private Row gameOption(String label, final String key, final int fallback) {
		Row r = new Row(label, K_SWITCH) {
			@Override String value() { return OFF_ON[checked() ? 1 : 0]; }
			@Override boolean checked() { return GsrNative.gameOption(key, fallback) != 0; }
			@Override void step(int dir) {
				GsrNative.setGameOption(key, GsrNative.gameOption(key, fallback) != 0 ? 0 : 1);
				refresh();
			}
		};
		r.refresh();
		return r;
	}

	private static final String[] ORIENTATION = { "Auto", "Landscape", "Portrait" };

	/** Auto (turn with the device), Landscape or Portrait. The menu reopens laid out for the new shape. */
	private Row orientation() {
		Row r = new Row("Orientation", K_CHIP) {
			@Override String value() { return ORIENTATION[GsrDisplay.orientationMode(activity)]; }
			@Override void step(int dir) {
				GsrDisplay.setOrientationMode(activity, (GsrDisplay.orientationMode(activity) + dir + 3) % 3);
				refresh();
				// The menu was laid out for the old shape of the screen: lay it out again once the screen has turned.
				reopen(500);
			}
		};
		r.refresh();
		return r;
	}

	/** An Off/On switch kept on the Java side (GsrDisplay preferences). */
	private Row javaSwitch(String label, final String key, final boolean fallback) {
		Row r = new Row(label, K_SWITCH) {
			@Override String value() { return OFF_ON[checked() ? 1 : 0]; }
			@Override boolean checked() { return GsrDisplay.flag(activity, key, fallback); }
			@Override void step(int dir) {
				GsrDisplay.setFlag(activity, key, !GsrDisplay.flag(activity, key, fallback));
				refresh();
			}
		};
		r.refresh();
		return r;
	}

	/** 2x frame interpolation: only offered on a screen that has a 120 Hz mode, and only used in the Native view. */
	private Row interpolation() {
		Row r = new Row("Frame interpolation", K_SWITCH) {
			private boolean capable() { return GsrDisplay.maxRefresh(activity) >= 100f; }
			@Override boolean checked() { return capable() && GsrNative.getSetting(GsrNative.INTERP) != 0; }
			@Override String value() {
				if (!capable()) return "Not available (needs a 120 Hz display)";
				if (GsrNative.getSetting(GsrNative.INTERP) == 0) return "Off";
				return GsrNative.getSetting(GsrNative.VIEW_MODE) == 0 ? "On (120 Hz)" : "On (Native view only)";
			}
			@Override String support() {
				if (!capable()) return "Not available: needs a 120 Hz display";
				if (GsrNative.getSetting(GsrNative.INTERP) == 0) return "Smoother motion at 120 Hz, in the Native view";
				return GsrNative.getSetting(GsrNative.VIEW_MODE) == 0 ? "On: 120 Hz" : "Only used in the Native view";
			}
			@Override void step(int dir) {
				if (!capable()) return;
				GsrNative.setSetting(GsrNative.INTERP, GsrNative.getSetting(GsrNative.INTERP) == 0 ? 1 : 0);
				GsrDisplay.apply(activity);
				refresh();
			}
			@Override void refresh() {
				super.refresh();
				setAlpha(capable() ? 1f : 0.45f);
			}
		};
		r.refresh();
		return r;
	}

	private static final int[] HIRES_VALUES = { 2, 3, 4, 6 };
	private static final String[] HIRES_LABELS = { "2x", "3x", "4x", "6x" };

	/** High-resolution rendering (the engine's own "native renderer"): the Native view drawn supersampled. */
	private Row highRes() {
		Row r = new Row("High-res rendering", K_SWITCH) {
			private boolean usable() {
				return GsrNative.getSetting(GsrNative.VIEW_MODE) == 0
						&& GsrNative.getSetting(GsrNative.INTERP) == 0;
			}
			@Override boolean checked() { return GsrNative.getSetting(GsrNative.NATIVE_RENDER) != 0; }
			@Override String value() {
				if (GsrNative.getSetting(GsrNative.NATIVE_RENDER) == 0) return "Off";
				if (GsrNative.getSetting(GsrNative.VIEW_MODE) != 0) return "On (Native view only)";
				if (GsrNative.getSetting(GsrNative.INTERP) != 0) return "On (off while frame interpolation is on)";
				return "On";
			}
			@Override String support() {
				if (GsrNative.getSetting(GsrNative.NATIVE_RENDER) == 0) return "Draws the Native view at a higher resolution";
				if (GsrNative.getSetting(GsrNative.VIEW_MODE) != 0) return "Only used in the Native view";
				if (GsrNative.getSetting(GsrNative.INTERP) != 0) return "Off while frame interpolation is on";
				return "The Native view, drawn at a higher resolution";
			}
			@Override void step(int dir) {
				GsrNative.setSetting(GsrNative.NATIVE_RENDER, GsrNative.getSetting(GsrNative.NATIVE_RENDER) == 0 ? 1 : 0);
				refresh();
			}
			@Override void refresh() {
				super.refresh();
				setAlpha(usable() || GsrNative.getSetting(GsrNative.NATIVE_RENDER) == 0 ? 1f : 0.55f);
			}
		};
		r.refresh();
		return r;
	}

	private static final String[] RENDERER = { "Auto", "CPU", "GPU (experimental)" };

	/** Auto (Golden Sun: CPU; The Lost Age: GPU in the Expanded view) / CPU / GPU (experimental). When the GPU renderer could not start on this device the row is greyed and stays on CPU. */
	private Row renderer() {
		Row r = new Row("Renderer", K_CHIP) {
			private boolean gpuOff() { return GsrNative.gpuStatus() == 2; }
			@Override String value() {
				if (gpuOff()) return "CPU (GPU not available)";
				int cur = GsrNative.getSetting(GsrNative.RENDERER);
				String now = GsrNative.gpuActive() == 1 ? "GPU" : "CPU";
				return cur == 0 ? "Auto (" + now + ")" : RENDERER[cur];
			}
			@Override void step(int dir) {
				if (gpuOff()) return;
				int i = (GsrNative.getSetting(GsrNative.RENDERER) + dir + 3) % 3;
				GsrNative.setSetting(GsrNative.RENDERER, i);
				refresh();
			}
			@Override void refresh() {
				super.refresh();
				setAlpha(gpuOff() ? 0.45f : 1f);
			}
		};
		r.refresh();
		return r;
	}

	/** A row that does something: ">" opens a page (chevron), "open" a link, "" is a plain action. */
	private Row action(String label, final String value, final Runnable run) {
		int kind = ">".equals(value) ? K_NAV : "open".equals(value) ? K_LINK : K_ACTION;
		Row r = new Row(label, kind) {
			@Override String value() { return value; }
			@Override void step(int dir) { if (dir > 0) run.run(); }
		};
		r.refresh();
		return r;
	}

	/** A row that opens one of the pages (in two-pane, the same as picking it in the list on the left). */
	private Row nav(String label, final int target) {
		return action(label, ">", new Runnable() {
			@Override public void run() { openPage(target); }
		});
	}

	private void openPage(int p) {
		if (!twoPane) returnKey = focusedKey();
		render(p, true);
	}

	/** Single pane: back to the main list, on the row that opened the page. */
	private void showMain() {
		render(P_MAIN, false);
		if (returnKey == null || !focusKey(returnKey)) focusFirst();
		returnKey = null;
	}

	// ---- The categories ------------------------------------------------------------------------------------------

	private void buildDisplay() {
		category("Display", P_DISPLAY);
		sub("View");
		choice("Screen view", GsrNative.VIEW_MODE, VIEW);
		// Fit screen's zoom only does something in the Fit screen view.
		if (GsrNative.getSetting(GsrNative.VIEW_MODE) == 2) {
			choice("Zoom (Fit screen)", GsrNative.VIEW_ZOOM, indexes(ZOOM.length), ZOOM, K_SLIDER);
		}
		orientation();
		choice("Scaling", GsrNative.SCALE_MODE, SCALE);
		choice("Pixels", GsrNative.FILTER, FILTER);
		sub("Picture");
		renderer();
		choice("Screen color", GsrNative.SCREEN, SCREEN_VALUES, SCREEN);
		// Listed in this order; the stored values are 0 off, 1 LCD, 2 CRT, 4 scanlines, 3 xBR.
		choice("Screen filter", GsrNative.SCREEN_FILTER, new int[] { 0, 1, 2, 4, 3 },
				new String[] { "Off", "LCD", "CRT", "Scanlines", "Smooth (xBR)" });
		choice("Flicker reduction", GsrNative.BLEND, FLICKER);
		interpolation();
		highRes();
		choice("High-res quality", GsrNative.NATIVE_SCALE, HIRES_VALUES, HIRES_LABELS);
		sub("Extras");
		choice("Show FPS", GsrNative.SHOW_FPS, OFF_ON);
		choice("Hide characters outside the room", GsrNative.ROOM_CLIP, ROOM_CLIP);
		sub("Pausing");
		javaSwitch("Pause when folding or unfolding", GsrDisplay.FLAG_PAUSE_ON_FOLD, true);
		javaSwitch("Pause behind the notification shade", GsrDisplay.FLAG_PAUSE_ON_FOCUS, true);
		sub("This screen");
		action("Reset this screen's settings", "", new Runnable() {
			@Override public void run() {
				GsrProfile.resetCurrent(activity);
				Toast.makeText(activity, "Settings for this screen reset", Toast.LENGTH_SHORT).show();
				list.post(new Runnable() {
					@Override public void run() { rebuild(); }
				});
			}
		}).supporting("Settings marked “This screen” are saved for: " + GsrProfile.label(activity));
	}

	private void buildSpeed() {
		category("Speed", P_SPEED);
		sub("Fast-forward");
		choice("Fast-forward speed", GsrNative.FF_MULT10, FF_VALUES, FF_LABELS, K_SLIDER);
		choice("Fast-forward button", GsrNative.FF_MODE, HOLD_TOGGLE);
		choice("As fast as possible", GsrNative.FF_UNCAPPED, OFF_ON);
		choice("Mute while fast-forwarding", GsrNative.FF_MUTE, OFF_ON);
	}

	private void buildAudio() {
		category("Audio", P_AUDIO);
		sub("Sound");
		int[] vols = new int[11];
		String[] volLabels = new String[11];
		for (int i = 0; i <= 10; i++) { vols[i] = i * 10; volLabels[i] = (i * 10) + "%"; }
		choice("Volume", GsrNative.VOLUME, vols, volLabels, K_SLIDER);
		choice("Mute", GsrNative.MUTE, OFF_ON);
	}

	private void buildControls() {
		category("Controls", P_CONTROLS);
		sub("Gamepad");
		nav("Gamepad buttons", P_BUTTONS);
		action("Reset gamepad buttons", "", new Runnable() {
			@Override public void run() {
				GsrNative.resetBinds();
				Toast.makeText(activity, "Gamepad buttons reset", Toast.LENGTH_SHORT).show();
			}
		});
		sub("Touch controls");
		choice("Direction control", GsrNative.TOUCH_DPAD, new String[] { "Joystick", "D-pad", "Floating D-pad" });
		choice("Tap for A", GsrNative.TAP_A, OFF_ON);
		choice("Two-finger tap for B", GsrNative.TWO_FINGER_B, OFF_ON);
		choice("Show A button", GsrNative.SHOW_A, OFF_ON);
		choice("Show B button", GsrNative.SHOW_B, OFF_ON);
		choice("Turbo A and B buttons", GsrNative.TURBO, OFF_ON);
		if (GsrNative.getSetting(GsrNative.TURBO) != 0) {
			int[] rates = { 3, 5, 8, 10, 12, 15, 20 };
			String[] rateLabels = new String[rates.length];
			for (int i = 0; i < rates.length; i++) rateLabels[i] = rates[i] + " per second";
			choice("Turbo speed", GsrNative.TURBO_RATE, rates, rateLabels, K_SLIDER);
		}
		choice("Touch controls size", GsrNative.TOUCH_SCALE, indexes(TOUCH_SIZE.length), TOUCH_SIZE, K_SLIDER);
		if (GsrDisplay.portrait(activity)) {
			// Compact: smaller and packed together, right under the picture or at the bottom of the screen, leaving
			// the rest of the screen free (for another app in a pop-up window, say).
			choice("Portrait controls", GsrNative.PORTRAIT_COMPACT,
					new String[] { "Spread", "Compact, under the picture", "Compact, at the bottom" });
		}
		int[] ops = { 5, 10, 15, 20, 30, 40, 50, 60, 70, 80, 90, 100 };
		String[] opLabels = new String[ops.length];
		for (int i = 0; i < ops.length; i++) opLabels[i] = ops[i] + "%";
		choice("Touch controls opacity", GsrNative.TOUCH_OPACITY, ops, opLabels, K_SLIDER);

		sub("Touch look");
		choice("Button style", GsrNative.TOUCH_STYLE, new String[] { "Flat", "Outlined", "GBA SP" });
		choice("Button colour", GsrNative.TOUCH_COLOR,
				new String[] { "Wallpaper", "Grey", "White", "Dark", "Blue", "Red", "Green", "Purple" });
		choice("Fade when idle", GsrNative.TOUCH_FADE,
				new String[] { "Never", "After 3 s", "After 5 s", "After 10 s", "After 20 s" });

		sub("Touch layout");
		action("Edit touch layout", "", new Runnable() {
			@Override public void run() {
				close();
				// After the menu has gone (its dialog gives the focus back first).
				activity.getWindow().getDecorView().postDelayed(new Runnable() {
					@Override public void run() {
						if (activity instanceof GsrGameActivity) ((GsrGameActivity) activity).startTouchEditor();
					}
				}, 250);
			}
		});
		action("Reset touch layout", "", new Runnable() {
			@Override public void run() {
				GsrNative.resetTouchLayout();
				Toast.makeText(activity, "Touch layout reset for this screen", Toast.LENGTH_SHORT).show();
			}
		});
	}

	private void buildGame() {
		category("Game", P_GAME);
		sub("Saving");
		nav("Save states", P_STATES);
		nav("Saves", P_SAVES);
		nav("Cheats", P_CHEATS);
		sub("Game options");
		gameOption("Enemy HP bar", "EnemyHpBar", 0);
		gameOption("Custom Battle Mode", "CustomBattle", 1);
		sub("Session");
		action("Resume", "", new Runnable() {
			@Override public void run() { close(); }
		});
		action("Switch game", "", new Runnable() {
			@Override public void run() {
				// Back to the launcher in a fresh process (the running engine cannot be unloaded), even with
				// "Start my last game straight away" on.
				android.content.Intent target = new android.content.Intent(activity, GsrLauncherActivity.class);
				target.putExtra(wootbeer.descore.DescoreLauncherActivity.EXTRA_SHOW_PICKER, true);
				android.content.Intent restart = new android.content.Intent(activity, GsrRestartActivity.class);
				restart.putExtra(GsrRestartActivity.EXTRA_PID, Process.myPid());
				restart.putExtra(GsrRestartActivity.EXTRA_TARGET, target);
				activity.startActivity(restart);
			}
		});
		action("Exit game", "", new Runnable() {
			@Override public void run() {
				activity.finishAffinity();
				Process.killProcess(Process.myPid());
			}
		});
		action("Change ROM", "", new Runnable() {
			@Override public void run() { confirmChangeRom(); }
		});
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** Confirmation dialogs in the same dark scheme as the menu. */
	private AlertDialog.Builder alert() {
		return new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
	}

	private void link(final String url) {
		action(url.replace("https://", ""), "open", new Runnable() {
			@Override public void run() {
				try {
					activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
							android.net.Uri.parse(url)));
				} catch (RuntimeException e) {
					Toast.makeText(activity, "No app can open " + url, Toast.LENGTH_SHORT).show();
				}
			}
		});
	}

	/** Cheats: infinite HP/PP and reward multipliers. Applied at once, and kept for this play session only. */
	private void buildCheats() {
		buildCat = P_CHEATS;
		section("Party");
		choice("Infinite HP", GsrNative.CHEAT_HP, OFF_ON);
		choice("Infinite PP", GsrNative.CHEAT_PP, OFF_ON);

		section("Battle rewards");
		choice("Experience", GsrNative.CHEAT_EXP, CHEAT_STEPS);
		choice("Coins", GsrNative.CHEAT_COIN, CHEAT_STEPS);
		choice("Drop chance", GsrNative.CHEAT_DROP, CHEAT_DROP_STEPS);
	}

	private static final int STATE_SLOTS = 3;

	/** True when this screen plays The Lost Age (the launcher's unit). */
	private boolean tla() { return GsrConfig.isTla(unit()); }
	/** The launcher unit: the game and its language. */
	private int unit() { return GsrConfig.unitOf(activity); }

	/** This game's folder under files/gsr and the name its ROM is stored under there. */
	private String gameDir() { return "gsr/" + GsrConfig.dirName(tla(), GsrConfig.lang(unit())) + "/"; }
	private String romName() { return tla() ? GsrConfig.TLA_ROM_NAME : GsrConfig.ROM_NAME; }

	/** The engine writes slot n beside the ROM as <rom>.state<n> (runtime.cpp slot_path). */
	private java.io.File stateFile(int slot) {
		String base = romName().substring(0, romName().lastIndexOf('.'));
		return new java.io.File(activity.getFilesDir(), gameDir() + base + ".state" + slot);
	}

	private String stateLabel(int slot) {
		java.io.File f = stateFile(slot);
		if (!f.isFile()) return "empty";
		return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
				.format(new java.util.Date(f.lastModified()));
	}

	/** Save states: the game's in-game saves are separate and untouched; these are instant snapshots. */
	private void buildStates() {
		buildCat = P_STATES;
		for (int i = 1; i <= STATE_SLOTS; i++) {
			final int slot = i;
			section("Slot " + slot + "  ·  " + stateLabel(slot));
			action("Save to slot " + slot, "", new Runnable() {
				@Override public void run() {
					if (stateFile(slot).isFile()) {
						alert()
								.setTitle("Overwrite slot " + slot + "?")
								.setMessage("The state saved there is replaced.")
								.setNegativeButton("Cancel", null)
								.setPositiveButton("Save", new DialogInterface.OnClickListener() {
									@Override public void onClick(DialogInterface d, int w) { saveState(slot); }
								})
								.show();
					} else {
						saveState(slot);
					}
				}
			});
			Row load = action("Load slot " + slot, "", new Runnable() {
				@Override public void run() {
					if (!stateFile(slot).isFile()) return;
					alert()
							.setTitle("Load slot " + slot + "?")
							.setMessage("Progress since your last save is lost.")
							.setNegativeButton("Cancel", null)
							.setPositiveButton("Load", new DialogInterface.OnClickListener() {
								@Override public void onClick(DialogInterface d, int w) {
									GsrNative.requestState(slot, false);
									// The engine takes the request while the menu still holds the game paused.
									list.postDelayed(new Runnable() {
										@Override public void run() { close(); }
									}, 300);
								}
							})
							.show();
				}
			});
			if (!stateFile(slot).isFile()) load.setAlpha(0.45f);
		}
		note("Save states are separate from the game's own saves and may not load after an app update.");
	}

	// ---- Saves: the games' own saves (import, export, backup) ------------------------------------------------

	static final int REQUEST_EXPORT = 4721, REQUEST_IMPORT = 4722, REQUEST_BACKUP_ALL = 4723;
	private GsrSaves saves;

	private GsrSaves saves() {
		if (saves == null) saves = new GsrSaves(activity);
		return saves;
	}

	private static String when(java.io.File f) {
		return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
				.format(new java.util.Date(f.lastModified()));
	}

	private String gameTitle() { return GsrConfig.title(tla(), GsrConfig.lang(unit())); }

	private void buildSaves() {
		buildCat = P_SAVES;
		final java.io.File cur = saves().saveFile(unit());
		final boolean have = cur.isFile() && cur.length() > 0;
		section(gameTitle() + " save  ·  " + (have ? "saved " + when(cur) : "none yet"));
		Row export = action("Export save...", "", new Runnable() {
			@Override public void run() {
				if (!have) return;
				android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
				i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
				i.setType("application/octet-stream");
				i.putExtra(android.content.Intent.EXTRA_TITLE, GsrSaves.saveName(unit()));
				startForResult(i, REQUEST_EXPORT);
			}
		});
		if (!have) export.setAlpha(0.45f);
		action("Import save...", "", new Runnable() {
			@Override public void run() {
				alert()
						.setTitle("Import a " + gameTitle() + " save?")
						.setMessage("Pick a .sav file from a GBA emulator, a flash cart or an export. It replaces the "
								+ "save in this app; the current one is kept as a backup first.\n\n"
								+ "The game closes afterwards. Open it again to play with the imported save.")
						.setNegativeButton("Cancel", null)
						.setPositiveButton("Choose file", new DialogInterface.OnClickListener() {
							@Override public void onClick(DialogInterface d, int w) {
								android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
								i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
								i.setType("*/*");
								startForResult(i, REQUEST_IMPORT);
							}
						})
						.show();
			}
		});
		final java.io.File english = saves().saveFile(GsrConfig.unitId(tla(), 0));
		if (GsrConfig.lang(unit()) != 0 && english.isFile() && english.length() > 0) {
			action("Copy the USA/Europe save", "", new Runnable() {
				@Override public void run() {
					alert()
							.setTitle("Copy the USA/Europe save?")
							.setMessage("Your " + GsrConfig.title(tla(), 0) + " (USA/Europe) save, from "
									+ when(english) + ", replaces the save here. The current one is kept as a "
									+ "backup.\n\nThe game closes afterwards. Open it again to play.")
							.setNegativeButton("Cancel", null)
							.setPositiveButton("Copy", new DialogInterface.OnClickListener() {
								@Override public void onClick(DialogInterface d, int w) {
									try {
										saves().copyFromEnglish(unit());
										closeGame("Save copied.");
									} catch (java.io.IOException e) {
										Toast.makeText(activity, "Could not copy: " + e.getMessage(), Toast.LENGTH_LONG).show();
									}
								}
							})
							.show();
				}
			});
		}
		final java.io.File[] old = saves().backups(unit());
		if (old.length > 0) {
			action("Restore a backup...", "", new Runnable() {
				@Override public void run() { chooseBackup(old); }
			});
		}

		section("Backup");
		action("Back up all saves to a folder...", "", new Runnable() {
			@Override public void run() {
				startForResult(new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_BACKUP_ALL);
			}
		}).supporting("Copies all your saves (both games, every language) into a new folder in the place you pick (for "
				+ "example Downloads, or a cloud drive), so they survive uninstalling the app. Save states are not included.");
	}

	private void startForResult(android.content.Intent i, int code) {
		try {
			activity.startActivityForResult(i, code);
		} catch (RuntimeException e) {
			Toast.makeText(activity, "No file browser on this device", Toast.LENGTH_LONG).show();
		}
	}

	private void chooseBackup(final java.io.File[] old) {
		String[] names = new String[old.length];
		for (int i = 0; i < old.length; i++) names[i] = "Replaced " + when(old[i]);
		alert()
				.setTitle("Restore which save?")
				.setItems(names, new DialogInterface.OnClickListener() {
					@Override public void onClick(DialogInterface d, final int which) {
						alert()
								.setTitle("Restore this save?")
								.setMessage("The current save is kept as a backup. The game closes afterwards; "
										+ "open it again to play.")
								.setNegativeButton("Cancel", null)
								.setPositiveButton("Restore", new DialogInterface.OnClickListener() {
									@Override public void onClick(DialogInterface d2, int w) {
										try {
											saves().restore(unit(), old[which]);
											closeGame("Save restored.");
										} catch (java.io.IOException e) {
											Toast.makeText(activity, "Could not restore: " + e.getMessage(), Toast.LENGTH_LONG).show();
										}
									}
								})
								.show();
					}
				})
				.setNegativeButton("Cancel", null)
				.show();
	}

	/** After a new save is in place: the running game still has the old one in memory and would write it back. */
	private void closeGame(String what) {
		alert()
				.setTitle(what)
				.setMessage("The game closes now. Open it again to play.")
				.setCancelable(false)
				.setPositiveButton("OK", new DialogInterface.OnClickListener() {
					@Override public void onClick(DialogInterface d, int w) {
						activity.finishAffinity();
						Process.killProcess(Process.myPid());
					}
				})
				.show();
	}

	/** From the activity's onActivityResult; true if it was one of the Saves page's requests. */
	boolean onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
		if (requestCode != REQUEST_EXPORT && requestCode != REQUEST_IMPORT && requestCode != REQUEST_BACKUP_ALL) return false;
		android.net.Uri uri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
		if (uri == null) return true;
		try {
			if (requestCode == REQUEST_EXPORT) {
				saves().exportTo(unit(), uri);
				Toast.makeText(activity, "Save exported", Toast.LENGTH_SHORT).show();
			} else if (requestCode == REQUEST_IMPORT) {
				saves().importFrom(unit(), uri);
				closeGame("Save imported.");
			} else {
				int n = saves().backupAllTo(uri);
				Toast.makeText(activity, n == 0 ? "There are no saves yet" : "Backed up " + n + (n == 1 ? " save" : " saves"),
						Toast.LENGTH_LONG).show();
			}
		} catch (Exception e) {
			Toast.makeText(activity, "Could not do that: " + e.getMessage(), Toast.LENGTH_LONG).show();
		}
		if (open && page == P_SAVES) rebuild();
		return true;
	}

	private void saveState(final int slot) {
		final long before = stateFile(slot).isFile() ? stateFile(slot).lastModified() : 0L;
		GsrNative.requestState(slot, true);
		// The engine writes the file at its next input pump (a frame or so, even while the menu pauses the game).
		list.postDelayed(new Runnable() {
			@Override public void run() {
				final boolean saved = stateFile(slot).isFile() && stateFile(slot).lastModified() != before;
				if (open && page == P_STATES) rebuild();
				Toast.makeText(activity, saved ? "Saved to slot " + slot : "Saving to slot " + slot + "...",
						Toast.LENGTH_SHORT).show();
			}
		}, 500);
	}

	/**
	 * Change ROM: after a confirmation, removes only this game's stored ROM file and closes the app, so the next
	 * start asks for it again. The save (beside the ROM) and the compiled game are kept: the only ROM this
	 * release accepts for each game always compiles to the same game, so picking it again does not rebuild.
	 */
	private void confirmChangeRom() {
		alert()
				.setTitle("Change ROM?")
				.setMessage("This removes the stored ROM and closes the game. Your save is kept.\n\n"
						+ "Open the app again to pick a new ROM file.")
				.setNegativeButton("Cancel", null)
				.setPositiveButton("Change ROM", new DialogInterface.OnClickListener() {
					@Override public void onClick(DialogInterface d, int which) {
						java.io.File rom = new java.io.File(activity.getFilesDir(), gameDir() + romName());
						if (rom.exists() && !rom.delete()) {
							Toast.makeText(activity, "Could not remove the ROM", Toast.LENGTH_LONG).show();
							return;
						}
						Toast.makeText(activity, "ROM removed. Open the app again to pick one.", Toast.LENGTH_LONG).show();
						activity.finishAffinity();
						Process.killProcess(Process.myPid());
					}
				})
				.show();
	}

	private void buildCredits() {
		buildCat = P_CREDITS;
		section("Android port");
		info("Android port by wootbeer", t.onSurface, 16);
		link("https://github.com/wootbeer");

		section("Golden Sun Recompiled");
		info("Golden Sun Recompiled by Shmargus", t.onSurface, 16);
		link("https://github.com/Shmargus/GSRecomp");
		info("Engine: gbarecomp by Matthew Stan", t.onSurface, 16);
		link("https://github.com/mstan/gbarecomp");

		section("Notices");
		info("Free and noncommercial. Contains no Golden Sun game data: your own ROM is translated and compiled "
				+ "on this device and never leaves it. Golden Sun and Golden Sun: The Lost Age belong to Nintendo and Camelot Software Planning.",
				t.onSurfaceVariant, 13);
		info("Built with mGBA and JRickey/gba-recomp (via gbarecomp) and TinyCC (LGPL). Full credits and licences "
				+ "are in the COPYING file of the source.", t.onSurfaceVariant, 13);

		section("Screen filters");
		info("LCD3x by Gigaherz and CRT Lottes by Timothy Lottes (public domain), and xBR-lv2 by Hyllian, ported "
				+ "by Golden Sun Recompiled from libretro's glsl-shaders.", t.onSurfaceVariant, 13);
		info("Hyllian's xBR-lv2 Shader\n\nCopyright (C) 2011-2016 Hyllian - sergiogdb@gmail.com\n\nPermission is hereby granted, free of charge, to any person obtaining a copy\nof this software and associated documentation files (the \"Software\"), to deal\nin the Software without restriction, including without limitation the rights\nto use, copy, modify, merge, publish, distribute, sublicense, and/or sell\ncopies of the Software, and to permit persons to whom the Software is\nfurnished to do so, subject to the following conditions:\n\nThe above copyright notice and this permission notice shall be included in\nall copies or substantial portions of the Software.\n\nTHE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\nIMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\nFITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\nAUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\nLIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\nOUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN\nTHE SOFTWARE.\n\nIncorporates some of the ideas from SABR shader. Thanks to Joshua Street.",
				DescoreTheme.alpha(t.onSurfaceVariant, 0.8f), 11);
	}

	private static String keyName(int code) {
		if (code == 0) return "not set";
		String n = KeyEvent.keyCodeToString(code);
		if (n.startsWith("KEYCODE_")) n = n.substring(8);
		if (n.startsWith("BUTTON_")) n = n.substring(7);
		return n;
	}

	private void buildButtons() {
		buildCat = P_BUTTONS;
		TextView first = note("Pick a row, then press the button you want. These only change the game; in menus A always "
				+ "selects and B always goes back.");
		first.setPadding(dp(16), 0, dp(16), dp(4));
		section("Game buttons");
		for (int a = 0; a < GsrNative.ACTIONS.length; a++) {
			for (int s = 0; s < 2; s++) {
				final int action = a, slot = s;
				final String name = GsrNative.ACTIONS[a] + (s == 1 ? " (second button)" : "");
				Row r = new Row(name, K_TEXT) {
					@Override String value() { return keyName(GsrNative.getBind(action, slot)); }
					@Override void step(int dir) { if (dir > 0) capture(action, slot, name, this); }
				};
				r.refresh();
			}
		}
		section("Defaults");
		action("Reset gamepad buttons", "", new Runnable() {
			@Override public void run() {
				GsrNative.resetBinds();
				rebuild();
			}
		});
	}

	private void capture(final int action, final int slot, String name, final Row row) {
		final AlertDialog d = alert()
				.setTitle("Press a button for " + name)
				.setMessage("Press the gamepad button to use. Back cancels.")
				.setNegativeButton("Clear", new DialogInterface.OnClickListener() {
					@Override public void onClick(DialogInterface dd, int which) {
						GsrNative.setBind(action, slot, 0);
						rebuild();
					}
				})
				.setPositiveButton("Cancel", null)
				.create();
		d.setOnKeyListener(new DialogInterface.OnKeyListener() {
			@Override
			public boolean onKey(DialogInterface dd, int keyCode, KeyEvent event) {
				if (keyCode == KeyEvent.KEYCODE_BACK) return false; // cancels
				if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
						|| keyCode == KeyEvent.KEYCODE_POWER || keyCode == KeyEvent.KEYCODE_UNKNOWN) return false;
				if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
					GsrNative.setBind(action, slot, keyCode);
					dd.dismiss();
					rebuild();
				}
				return true;
			}
		});
		d.show();
	}
}
