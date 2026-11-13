package wootbeer.descore;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.LinearLayout;

/**
 * Small Material 3 style controls drawn by hand (no libraries): a switch, a slider, line icons, a linear progress
 * indicator and a column that stops growing past a maximum size. They take their colours from {@link DescoreTheme}.
 * None of them takes focus: the row or screen around them does, so a gamepad moves between rows, not into them.
 */
public final class DescoreWidgets {
	private DescoreWidgets() {}

	private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

	// ---------------------------------------------------------------------------------------------------------------

	/** An M3 switch: a 52x32 dp track with a thumb that grows and slides to the right when on. */
	public static final class Switch extends View {
		private final DescoreTheme t;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF r = new RectF();
		private boolean checked;
		private float pos; // 0 off .. 1 on
		private ValueAnimator anim;

		public Switch(Context c, DescoreTheme t) {
			super(c);
			this.t = t;
			setFocusable(false);
			setClickable(false);
			setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
		}

		public boolean isChecked() { return checked; }

		public void setChecked(boolean on, boolean animate) {
			if (anim != null) anim.cancel();
			if (on == checked && !animate) { pos = on ? 1f : 0f; invalidate(); return; }
			checked = on;
			if (!animate) {
				pos = on ? 1f : 0f;
				invalidate();
				return;
			}
			anim = ValueAnimator.ofFloat(pos, on ? 1f : 0f);
			anim.setDuration(150);
			anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
				@Override public void onAnimationUpdate(ValueAnimator a) {
					pos = (Float) a.getAnimatedValue();
					invalidate();
				}
			});
			anim.start();
		}

		@Override
		protected void onMeasure(int w, int h) {
			setMeasuredDimension(t.dp(52), t.dp(32));
		}

		@Override
		protected void onDraw(Canvas c) {
			float w = getWidth(), h = getHeight(), half = h / 2f, sw = t.dp(2);
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(DescoreTheme.blend(t.surfaceContainerHighest, t.primary, pos));
			r.set(0, 0, w, h);
			c.drawRoundRect(r, half, half, paint);
			if (pos < 1f) {
				paint.setStyle(Paint.Style.STROKE);
				paint.setStrokeWidth(sw);
				paint.setColor(DescoreTheme.alpha(t.outline, 1f - pos));
				r.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2);
				c.drawRoundRect(r, half - sw / 2, half - sw / 2, paint);
			}
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(DescoreTheme.blend(t.outline, t.onPrimary, pos));
			float radius = lerp(t.dp(8), t.dp(12), pos);
			c.drawCircle(lerp(half, w - half, pos), half, radius, paint);
		}
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** An M3 slider with a stop per value. Touch and drag picks a stop; the listener hears every change. */
	public static final class Slider extends View {
		public interface Listener { void onPick(int index); }

		private final DescoreTheme t;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF r = new RectF();
		private int count = 2, index;
		private boolean dragging, focusedLook;
		private Listener listener;

		public Slider(Context c, DescoreTheme t) {
			super(c);
			this.t = t;
			setFocusable(false);
		}

		public void setListener(Listener l) { listener = l; }

		public void setCount(int n) { count = Math.max(2, n); invalidate(); }

		public void setIndex(int i) {
			index = Math.max(0, Math.min(count - 1, i));
			invalidate();
		}

		/** Colours for a slider inside a focused (secondaryContainer) row. */
		public void setFocusedLook(boolean f) { focusedLook = f; invalidate(); }

		@Override
		protected void onMeasure(int w, int h) {
			setMeasuredDimension(getDefaultSize(t.dp(160), w), t.dp(36));
		}

		private float pad() { return t.dp(12); }

		private float x(int i) { return pad() + (getWidth() - 2 * pad()) * i / (float) (count - 1); }

		@Override
		protected void onDraw(Canvas c) {
			float y = getHeight() / 2f, th = t.dp(3), x = x(index);
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(focusedLook ? DescoreTheme.alpha(t.onSecondaryContainer, 0.3f) : t.secondaryContainer);
			r.set(pad(), y - th, getWidth() - pad(), y + th);
			c.drawRoundRect(r, th, th, paint);
			paint.setColor(t.primary);
			r.set(pad(), y - th, x, y + th);
			c.drawRoundRect(r, th, th, paint);
			if (count <= 16) {
				float dot = t.dp(1.5f);
				for (int i = 0; i < count; i++) {
					if (i == index) continue;
					paint.setColor(i < index ? DescoreTheme.alpha(t.onPrimary, 0.8f)
							: DescoreTheme.alpha(focusedLook ? t.onSecondaryContainer : t.onSurfaceVariant, 0.7f));
					c.drawCircle(x(i), y, dot, paint);
				}
			}
			if (dragging) {
				paint.setColor(DescoreTheme.alpha(t.primary, 0.2f));
				c.drawCircle(x, y, t.dp(18), paint);
			}
			paint.setColor(t.primary);
			c.drawCircle(x, y, t.dp(10), paint);
		}

		@Override
		public boolean onTouchEvent(MotionEvent e) {
			if (!isEnabled()) return false;
			switch (e.getActionMasked()) {
				case MotionEvent.ACTION_DOWN:
					ViewParent p = getParent();
					if (p != null) p.requestDisallowInterceptTouchEvent(true);
					dragging = true;
					pick(e.getX());
					return true;
				case MotionEvent.ACTION_MOVE:
					pick(e.getX());
					return true;
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					dragging = false;
					invalidate();
					return true;
				default:
					return true;
			}
		}

		private void pick(float px) {
			float span = getWidth() - 2 * pad();
			int i = span <= 0 ? index : Math.round((px - pad()) / span * (count - 1));
			i = Math.max(0, Math.min(count - 1, i));
			if (i != index) {
				index = i;
				if (listener != null) listener.onPick(i);
			}
			invalidate();
		}
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** Line icons drawn on a 24-unit grid, so no icon font or image is needed. */
	public static final class Icon extends View {
		public static final int CLOSE = 0, BACK = 1, CHEVRON_RIGHT = 2, CHEVRON_LEFT = 3, OPEN = 4, PLAY = 5, ADD = 6;

		private final DescoreTheme t;
		private final int kind, size;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path path = new Path();

		/** {@code sizeDp}: the drawn icon; padding adds to it (for a bigger touch target). */
		public Icon(Context c, DescoreTheme t, int kind, float sizeDp, int color) {
			super(c);
			this.t = t;
			this.kind = kind;
			this.size = t.dp(sizeDp);
			paint.setColor(color);
			paint.setStrokeCap(Paint.Cap.ROUND);
			paint.setStrokeJoin(Paint.Join.ROUND);
			setFocusable(false);
		}

		public void setColor(int color) {
			paint.setColor(color);
			invalidate();
		}

		@Override
		protected void onMeasure(int w, int h) {
			setMeasuredDimension(size + getPaddingLeft() + getPaddingRight(), size + getPaddingTop() + getPaddingBottom());
		}

		@Override
		protected void onDraw(Canvas c) {
			float s = size / 24f;
			float ox = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - size) / 2f;
			float oy = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - size) / 2f;
			c.save();
			c.translate(ox, oy);
			c.scale(s, s);
			path.reset();
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(2f);
			switch (kind) {
				case CLOSE:
					path.moveTo(6, 6); path.lineTo(18, 18);
					path.moveTo(18, 6); path.lineTo(6, 18);
					break;
				case BACK:
					path.moveTo(20, 12); path.lineTo(4.5f, 12);
					path.moveTo(11, 5.5f); path.lineTo(4.5f, 12); path.lineTo(11, 18.5f);
					break;
				case CHEVRON_RIGHT:
					path.moveTo(9, 6); path.lineTo(15, 12); path.lineTo(9, 18);
					break;
				case CHEVRON_LEFT:
					path.moveTo(15, 6); path.lineTo(9, 12); path.lineTo(15, 18);
					break;
				case OPEN:
					path.moveTo(7, 17); path.lineTo(17, 7);
					path.moveTo(9, 7); path.lineTo(17, 7); path.lineTo(17, 15);
					break;
				case ADD:
					path.moveTo(12, 5); path.lineTo(12, 19);
					path.moveTo(5, 12); path.lineTo(19, 12);
					break;
				case PLAY:
				default:
					paint.setStyle(Paint.Style.FILL);
					path.moveTo(8, 5); path.lineTo(19, 12); path.lineTo(8, 19); path.close();
					break;
			}
			c.drawPath(path, paint);
			c.restore();
		}
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** An M3 linear progress indicator (determinate), drawn as a rounded track with a rounded indicator. */
	public static final class Progress extends View {
		private final DescoreTheme t;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF r = new RectF();
		private int max = 100, progress;

		public Progress(Context c, DescoreTheme t) {
			super(c);
			this.t = t;
			setFocusable(false);
		}

		public void setMax(int m) { max = Math.max(1, m); invalidate(); }

		public void setProgress(int p) { progress = Math.max(0, Math.min(max, p)); invalidate(); }

		@Override
		protected void onMeasure(int w, int h) {
			setMeasuredDimension(getDefaultSize(t.dp(240), w), t.dp(8));
		}

		@Override
		protected void onDraw(Canvas c) {
			float h = getHeight(), rad = h / 2f, w = getWidth();
			float end = w * progress / (float) max;
			paint.setColor(t.secondaryContainer);
			r.set(Math.min(w, end + (progress > 0 ? t.dp(4) : 0)), 0, w, h);
			if (r.width() > 0) c.drawRoundRect(r, rad, rad, paint);
			if (progress > 0) {
				paint.setColor(t.primary);
				r.set(0, 0, Math.max(h, end), h);
				c.drawRoundRect(r, rad, rad, paint);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** A LinearLayout that never grows wider or taller than the maximum it is given (0: no limit). */
	public static class Column extends LinearLayout {
		private int maxWidth, maxHeight;

		public Column(Context c) { super(c); }

		public void setMaxSize(int widthPx, int heightPx) {
			maxWidth = widthPx;
			maxHeight = heightPx;
			requestLayout();
		}

		@Override
		protected void onMeasure(int w, int h) {
			if (maxWidth > 0 && MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(w) > maxWidth) {
				w = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.getMode(w));
			}
			if (maxHeight > 0 && MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(h) > maxHeight) {
				h = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.getMode(h));
			}
			super.onMeasure(w, h);
		}
	}
}
