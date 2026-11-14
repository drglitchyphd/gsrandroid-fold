package wootbeer.gsrandroid;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import wootbeer.descore.DescoreTheme;
import wootbeer.descore.DescoreWidgets;

/**
 * Settings > Controls > Edit touch layout. The game pauses and every touch control is shown; the native side
 * (gsr_touch.c) picks the control under a finger and moves it as the finger drags. This class adds a small
 * floating bar for the rest: the selected control's size and whether it is shown, Reset, Cancel and Save. The bar
 * can be dragged by its title so it never sits on the control being placed. Each screen and orientation keeps its
 * own layout (GsrProfile).
 */
final class GsrTouchEditor {
	/** Sizes offered on the slider, relative to the control's default size. */
	private static final float[] SIZES = { 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.4f, 1.6f, 1.8f, 2.0f, 2.5f };

	private final Activity activity;
	private final DescoreTheme t;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable poll = new Runnable() {
		@Override public void run() {
			int sel = GsrNative.editSelected();
			if (sel != selected) {
				selected = sel;
				refresh();
			}
			handler.postDelayed(this, 120);
		}
	};
	private final Runnable onDone;
	private FrameLayout root;
	private LinearLayout bar;
	private TextView title;
	private DescoreWidgets.Slider size;
	private DescoreWidgets.Switch shown;
	private View controls;
	private int selected = -2;
	private float downX, downY, barX, barY;

	GsrTouchEditor(Activity activity, Runnable onDone) {
		this.activity = activity;
		this.onDone = onDone;
		this.t = DescoreTheme.get(activity);
	}

	void start() {
		GsrNative.setUserPause(true);
		GsrNative.editBegin();

		root = new FrameLayout(activity);  // not clickable: touches outside the bar reach the game surface
		bar = new LinearLayout(activity);
		bar.setOrientation(LinearLayout.VERTICAL);
		bar.setBackground(t.rounded(t.surfaceContainerHigh, 24));
		bar.setPadding(t.dp(16), t.dp(10), t.dp(16), t.dp(12));
		bar.setElevation(t.dp(6));
		bar.setClickable(true);

		title = text("", t.onSurface, 16, true);
		title.setPadding(0, t.dp(4), 0, t.dp(8));
		title.setOnTouchListener(new View.OnTouchListener() {
			@Override public boolean onTouch(View v, MotionEvent e) { return drag(e); }
		});
		bar.addView(title);

		LinearLayout row = new LinearLayout(activity);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(text("Size", t.onSurfaceVariant, 14, false));
		size = new DescoreWidgets.Slider(activity, t);
		size.setCount(SIZES.length);
		size.setListener(new DescoreWidgets.Slider.Listener() {
			@Override public void onPick(int index) { GsrNative.editSetSize(SIZES[index]); }
		});
		row.addView(size, new LinearLayout.LayoutParams(t.dp(200), ViewGroup.LayoutParams.WRAP_CONTENT));
		row.addView(text("Shown", t.onSurfaceVariant, 14, false), lp(t.dp(8)));
		shown = new DescoreWidgets.Switch(activity, t);
		shown.setClickable(true);
		shown.setOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View v) {
				boolean on = !shown.isChecked();
				shown.setChecked(on, true);
				GsrNative.editSetShown(on);
			}
		});
		row.addView(shown, lp(t.dp(8)));
		controls = row;
		bar.addView(row);

		LinearLayout buttons = new LinearLayout(activity);
		buttons.setOrientation(LinearLayout.HORIZONTAL);
		buttons.setGravity(Gravity.END);
		buttons.setPadding(0, t.dp(8), 0, 0);
		buttons.addView(button("Reset", false, new Runnable() {
			@Override public void run() {
				GsrNative.editReset();
				refresh();
			}
		}));
		buttons.addView(button("Cancel", false, new Runnable() {
			@Override public void run() { finish(false); }
		}), lp(t.dp(8)));
		buttons.addView(button("Save", true, new Runnable() {
			@Override public void run() { finish(true); }
		}), lp(t.dp(8)));
		bar.addView(buttons);

		FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
		blp.bottomMargin = t.dp(16);
		root.addView(bar, blp);
		activity.addContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		restorePosition();
		refresh();
		handler.post(poll);
	}

	/** Back: leave without saving. */
	void cancel() { finish(false); }

	private void finish(boolean save) {
		handler.removeCallbacks(poll);
		GsrNative.editEnd(save);
		savePosition();
		if (root != null && root.getParent() != null) ((ViewGroup) root.getParent()).removeView(root);
		root = null;
		android.widget.Toast.makeText(activity, save ? "Touch layout saved for this screen" : "Touch layout not changed",
				android.widget.Toast.LENGTH_SHORT).show();
		if (onDone != null) onDone.run();
	}

	private void refresh() {
		if (selected < 0) {
			title.setText("Drag a control to move it  ·  " + GsrProfile.label(activity));
			controls.setAlpha(0.4f);
			size.setEnabled(false);
			shown.setEnabled(false);
			return;
		}
		title.setText(GsrNative.touchLabel(selected) + "  ·  drag here to move this bar");
		controls.setAlpha(1f);
		size.setEnabled(true);
		shown.setEnabled(true);
		float s = GsrNative.editGetSize();
		int best = 0;
		for (int i = 0; i < SIZES.length; i++) if (Math.abs(SIZES[i] - s) < Math.abs(SIZES[best] - s)) best = i;
		size.setIndex(best);
		shown.setChecked(GsrNative.editGetShown(), false);
	}

	// ---- the bar can be dragged by its title; where it was is remembered per screen ------------------------------

	private boolean drag(MotionEvent e) {
		switch (e.getActionMasked()) {
		case MotionEvent.ACTION_DOWN:
			downX = e.getRawX(); downY = e.getRawY();
			barX = bar.getTranslationX(); barY = bar.getTranslationY();
			return true;
		case MotionEvent.ACTION_MOVE:
			bar.setTranslationX(barX + e.getRawX() - downX);
			bar.setTranslationY(barY + e.getRawY() - downY);
			return true;
		default:
			return true;
		}
	}

	private String posKey() {
		return "editor_bar_" + GsrProfile.key(activity.getResources().getConfiguration());
	}

	private void savePosition() {
		if (bar == null) return;
		activity.getSharedPreferences("gsr_display", Activity.MODE_PRIVATE).edit()
				.putFloat(posKey() + "_x", bar.getTranslationX()).putFloat(posKey() + "_y", bar.getTranslationY()).apply();
	}

	private void restorePosition() {
		android.content.SharedPreferences p = activity.getSharedPreferences("gsr_display", Activity.MODE_PRIVATE);
		bar.setTranslationX(p.getFloat(posKey() + "_x", 0f));
		bar.setTranslationY(p.getFloat(posKey() + "_y", 0f));
	}

	// ---- small helpers -------------------------------------------------------------------------------------------

	private TextView text(String s, int color, float sp, boolean medium) {
		TextView v = new TextView(activity);
		v.setText(s);
		v.setTextColor(color);
		v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		if (medium) v.setTypeface(DescoreTheme.medium());
		return v;
	}

	private LinearLayout.LayoutParams lp(int left) {
		LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		p.leftMargin = left;
		return p;
	}

	private TextView button(String label, boolean filled, final Runnable run) {
		TextView b = text(label, filled ? t.onPrimary : t.primary, 14, true);
		b.setGravity(Gravity.CENTER);
		b.setMinHeight(t.dp(40));
		b.setPadding(t.dp(18), 0, t.dp(18), 0);
		b.setBackground(filled ? t.pressable(t.primary, 20) : t.pressable(0x00000000, 20));
		b.setClickable(true);
		b.setOnClickListener(new View.OnClickListener() {
			@Override public void onClick(View v) { run.run(); }
		});
		return b;
	}
}
