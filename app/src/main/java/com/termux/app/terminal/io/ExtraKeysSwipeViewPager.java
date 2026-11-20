package com.termux.app.terminal.io;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

import com.termux.app.terminal.io.ExtraKeysSwipeDetector.Direction;
import com.termux.app.terminal.io.ExtraKeysSwipeDetector.Result;

/**
 * The pager of the terminal toolbar: the normal extra keys row on page 0 and the text input on
 * page 1.
 *
 * Horizontal swipes over the normal row work like swipe actions on list items (Gmail, Todoist):
 * the row follows the finger, and the strip it uncovers at the edge the finger comes from shows
 * the action that releasing runs. Too short a swipe shows the action dimmed and is called off on
 * release; past the swipe distance the strip turns plum and names the swipe's action; past the
 * long swipe distance, if the direction has a long swipe, it turns lavender and names that action,
 * with a tick. The action runs on release, then the row snaps back.
 *
 * The pager itself never drags from the normal row to the text input: that is the
 * {@code toggle-text-input} action. From the text input, dragging back to the normal row is the
 * pager's own, untouched. Once a drag is a swipe, the keys get a cancel, so that no key is pressed;
 * everything else is passed on, so key presses, key repeat and popups keep working.
 */
public class ExtraKeysSwipeViewPager extends ViewPager {

    /** How far the finger moves before the row follows it, in dp. */
    static final float DRAG_START_DP = 12;

    /** The swipe distance: this fraction of the row's width, but at least {@link #MIN_SWIPE_DP}. */
    static final float SWIPE_FRACTION = 0.2f;
    static final float MIN_SWIPE_DP = 64;

    /** The long swipe distance: this fraction of the row's width, but at least twice the swipe distance. */
    static final float LONG_SWIPE_FRACTION = 0.5f;

    /** The duration of the snap back at the end of a swipe. */
    static final long RELEASE_DURATION_MS = 200;

    /* The toolbar is always black: TermuxPP's palette. */
    private static final int STRIP_IDLE_COLOR = 0xFF1E1A24;
    private static final int STRIP_SWIPE_COLOR = 0xFF3B1D6A;
    private static final int STRIP_LONG_COLOR = 0xFFD8B4FE;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int TEXT_ON_LONG_COLOR = 0xFF2A0F55;

    public interface Listener extends ExtraKeysSwipeDetector.Bindings {

        /** A swipe over the normal extra keys row is done. */
        void onSwipe(@NonNull Direction direction, boolean longSwipe);

        /**
         * Whether a swipe has an action right now. Asked for each touch: without one, the swipe is
         * not recognized at all and nothing is consumed or drawn.
         */
        @Override
        default boolean hasSwipe(@NonNull Direction direction) {
            return true;
        }

        /** Whether the direction has a long swipe right now. */
        @Override
        default boolean hasLongSwipe(@NonNull Direction direction) {
            return false;
        }

        /** The name of the action of a swipe for the strip, or {@code null} if it has none. */
        @Nullable
        default String getSwipeLabel(@NonNull Direction direction, boolean longSwipe) {
            return null;
        }

        /** Whether the row may follow the finger. The strip is always shown. */
        default boolean isFeedbackEnabled() {
            return true;
        }
    }

    private final ExtraKeysSwipeDetector mSwipeDetector;
    private Listener mListener;

    /** Set when the running touch is a swipe, which then owns the touch. */
    private boolean mSwallowTouch;

    /** Set when the running touch started on the settled normal row, where the pager may not drag. */
    private boolean mPagingBlocked;

    private ValueAnimator mReleaseAnimator;

    /* The strip of a followed swipe. */
    private final Paint mStripPaint = new Paint();
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSubLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity;
    @Nullable private Direction mStripDirection;
    @Nullable private String mSwipeLabel;
    @Nullable private String mLongLabel;
    /** How wide the uncovered strip is, in px. */
    private float mStripWidth;
    @NonNull private Result mStripPending = Result.NONE;
    private float mStripAlpha = 1f;

    public ExtraKeysSwipeViewPager(@NonNull Context context) {
        this(context, null);
    }

    public ExtraKeysSwipeViewPager(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        float minDistance = MIN_SWIPE_DP * mDensity;
        mSwipeDetector = new ExtraKeysSwipeDetector(DRAG_START_DP * mDensity, minDistance, 2 * minDistance, touchSlop);

        mLabelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mLabelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
            context.getResources().getDisplayMetrics()));
        mSubLabelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11,
            context.getResources().getDisplayMetrics()));
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float minDistance = Math.max(MIN_SWIPE_DP * mDensity, SWIPE_FRACTION * width);
        mSwipeDetector.setDistances(minDistance, Math.max(2 * minDistance, LONG_SWIPE_FRACTION * width));
    }

    /** The swipe distance in px, for tests. */
    float getSwipeDistance() {
        return mSwipeDetector.getMinDistance();
    }

    /** The long swipe distance in px, for tests. */
    float getLongSwipeDistance() {
        return mSwipeDetector.getLongDistance();
    }

    private boolean isOnSettledNormalRow() {
        return getCurrentItem() == 0 && getScrollX() == 0;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mSwallowTouch = false;
                // Only on the settled normal row: not while the text input is selected or the
                // pager is still scrolling or settling on its way there or back.
                boolean eligible = mListener != null && isOnSettledNormalRow();
                mPagingBlocked = eligible;
                mSwipeDetector.onDown(event.getX(), event.getY(), eligible ? mListener : null);
                break;

            case MotionEvent.ACTION_MOVE:
                if (mSwipeDetector.isFollowing()) {
                    mSwipeDetector.onMove(event.getX(), event.getY());
                    updateFollow();
                    return true;
                }
                if (mSwallowTouch) return true;
                if (mSwipeDetector.onMove(event.getX(), event.getY()) == Result.RECOGNIZED
                    && getCurrentItem() == 0) {
                    mSwallowTouch = true;
                    cancelTouchOfChildren(event);
                    startFollow();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
                if (mSwipeDetector.isFollowing()) {
                    finishFollow(mSwipeDetector.onUp(event.getX(), event.getY()));
                    return true;
                }
                mSwipeDetector.onUp(event.getX(), event.getY());
                if (mSwallowTouch) return true;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                if (mSwipeDetector.isFollowing()) {
                    mSwipeDetector.cancel();
                    finishFollow(Result.NONE);
                    return true;
                }
                mSwipeDetector.cancel();
                if (mSwallowTouch) return true;
                break;

            default:
                if (mSwallowTouch) return true;
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    /** No drag of the pager from the normal row: both directions are swipes there. */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mPagingBlocked) return false;
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mPagingBlocked) return false;
        return super.onTouchEvent(event);
    }

    /** Releases the pressed key and the pager's drag, as if the touch had been taken away from them. */
    private void cancelTouchOfChildren(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        try {
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
    }



    /* The followed swipe. */

    private void startFollow() {
        stopRelease();
        Direction direction = mSwipeDetector.getDirection();
        mStripDirection = direction;
        mSwipeLabel = mListener == null || direction == null || !mSwipeDetector.hasSwipe()
            ? null : mListener.getSwipeLabel(direction, false);
        mLongLabel = mListener == null || direction == null || !mSwipeDetector.hasLongSwipe()
            ? null : mListener.getSwipeLabel(direction, true);
        mStripPending = Result.NONE;
        mStripAlpha = 1f;
        updateFollow();
    }

    private void updateFollow() {
        if (mStripDirection == null) return;
        Result pending = mSwipeDetector.getPending();
        if (pending != mStripPending) {
            // A tick when releasing would now do something else.
            if (pending != Result.NONE) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            mStripPending = pending;
        }
        mStripWidth = Math.max(0f, Math.min(getWidth(), mSwipeDetector.getDistance()));
        float translation = mListener != null && mListener.isFeedbackEnabled() ? mStripWidth : 0f;
        setPagesTranslationX(mStripDirection.sign() * translation);
        invalidate();
    }

    private void finishFollow(@NonNull Result result) {
        Direction direction = mStripDirection;
        mStripPending = result;
        startRelease();
        if (direction != null && mListener != null && (result == Result.SWIPE || result == Result.LONG_SWIPE)) {
            mListener.onSwipe(direction, result == Result.LONG_SWIPE);
        }
    }

    /** Lets the row snap back and the strip close with it, from wherever they are. */
    private void startRelease() {
        ValueAnimator previous = mReleaseAnimator;
        mReleaseAnimator = null;
        if (previous != null) previous.cancel();

        View page = currentPage();
        final float startTranslation = page == null ? 0f : page.getTranslationX();
        final float startStrip = mStripWidth;
        if (!areSystemAnimationsEnabled()) {
            clearStrip();
            setPagesTranslationX(0f);
            return;
        }

        final ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
        animator.setDuration(RELEASE_DURATION_MS);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(a -> {
            float remaining = (float) a.getAnimatedValue();
            setPagesTranslationX(startTranslation * remaining);
            mStripWidth = startStrip * remaining;
            mStripAlpha = remaining;
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (mReleaseAnimator == animator) {
                    mReleaseAnimator = null;
                    clearStrip();
                    setPagesTranslationX(0f);
                }
            }
        });
        mReleaseAnimator = animator;
        animator.start();
    }

    private void stopRelease() {
        ValueAnimator animator = mReleaseAnimator;
        mReleaseAnimator = null;
        if (animator != null) animator.cancel();
        clearStrip();
        setPagesTranslationX(0f);
    }

    private void clearStrip() {
        mStripDirection = null;
        mSwipeLabel = null;
        mLongLabel = null;
        mStripWidth = 0f;
        mStripPending = Result.NONE;
        mStripAlpha = 1f;
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (mStripDirection != null && mStripWidth > 0f) drawStrip(canvas);
    }

    /**
     * The strip the row uncovers, at the edge the finger comes from: its color and label tell what
     * releasing runs. The label is anchored at that edge and revealed as the row moves.
     */
    private void drawStrip(@NonNull Canvas canvas) {
        final Direction direction = mStripDirection;
        if (direction == null) return;
        final float left = getScrollX();
        final float width = getWidth();
        final float height = getHeight();
        final boolean fromLeft = direction == Direction.LEFT;
        final float stripLeft = fromLeft ? left : left + width - mStripWidth;
        final float stripRight = fromLeft ? left + mStripWidth : left + width;
        final int alpha = Math.round(255 * Math.max(0f, Math.min(1f, mStripAlpha)));

        final Result pending = mStripPending;
        final int color;
        final int textColor;
        final String label;
        final float labelOpacity;
        if (pending == Result.LONG_SWIPE) {
            color = STRIP_LONG_COLOR;
            textColor = TEXT_ON_LONG_COLOR;
            label = mLongLabel;
            labelOpacity = 1f;
        } else if (pending == Result.SWIPE) {
            color = STRIP_SWIPE_COLOR;
            textColor = TEXT_COLOR;
            label = mSwipeLabel;
            labelOpacity = 1f;
        } else {
            // Not far enough yet: what the swipe would do, dimmed.
            color = STRIP_IDLE_COLOR;
            textColor = TEXT_COLOR;
            label = mSwipeLabel != null ? mSwipeLabel : mLongLabel;
            labelOpacity = 0.55f;
        }

        canvas.save();
        canvas.clipRect(stripLeft, 0, stripRight, height);
        mStripPaint.setColor(color);
        mStripPaint.setAlpha(Math.round(alpha * (Color.alpha(color) / 255f)));
        canvas.drawRect(stripLeft, 0, stripRight, height, mStripPaint);

        if (label != null) {
            final String arrow = pending == Result.LONG_SWIPE ? "»" : "›";
            final String text = fromLeft ? label + " " + arrow : (arrow.equals("»") ? "«" : "‹") + " " + label;
            // What lies further, while the swipe is armed and the long swipe not reached yet.
            final String sub = pending == Result.SWIPE && mLongLabel != null ? "further: " + mLongLabel : null;
            final float pad = 16 * mDensity;
            final Paint.FontMetrics labelMetrics = mLabelPaint.getFontMetrics();
            final Paint.FontMetrics subMetrics = mSubLabelPaint.getFontMetrics();
            final float labelHeight = labelMetrics.descent - labelMetrics.ascent;
            final float subHeight = subMetrics.descent - subMetrics.ascent;
            final boolean showSub = sub != null && labelHeight + subHeight + 2 * mDensity <= height - 4 * mDensity;
            final float contentHeight = labelHeight + (showSub ? 2 * mDensity + subHeight : 0);
            final float baseline = height / 2f - contentHeight / 2f - labelMetrics.ascent;

            mLabelPaint.setColor(textColor);
            mLabelPaint.setAlpha(Math.round(alpha * labelOpacity));
            mLabelPaint.setTextAlign(fromLeft ? Paint.Align.LEFT : Paint.Align.RIGHT);
            final float x = fromLeft ? left + pad : left + width - pad;
            canvas.drawText(text, x, baseline, mLabelPaint);
            if (showSub) {
                mSubLabelPaint.setColor(textColor);
                mSubLabelPaint.setAlpha(Math.round(alpha * 0.7f));
                mSubLabelPaint.setTextAlign(fromLeft ? Paint.Align.LEFT : Paint.Align.RIGHT);
                canvas.drawText(sub, x, baseline + labelMetrics.descent + 2 * mDensity - subMetrics.ascent, mSubLabelPaint);
            }
        }
        canvas.restore();
    }

    /**
     * Slides only the page that is shown. The other pages, like the text input right of the extra
     * keys, stay where the pager put them, off screen: moving them along would pull them into view.
     */
    private void setPagesTranslationX(float translationX) {
        View shown = currentPage();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            float wanted = child == shown ? translationX : 0f;
            if (child.getTranslationX() != wanted) child.setTranslationX(wanted);
        }
    }

    /** The page that fills the pager at its current scroll position, or null. */
    @Nullable
    private View currentPage() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getLeft() == getScrollX()) return child;
        }
        return null;
    }

    private boolean areSystemAnimationsEnabled() {
        return Settings.Global.getFloat(getContext().getContentResolver(),
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    /** The running snap back or null, for tests. */
    ValueAnimator getReleaseAnimator() {
        return mReleaseAnimator;
    }

    /** Whether the strip of a swipe is shown, for tests. */
    boolean isStripShown() {
        return mStripDirection != null;
    }

    /** What releasing now would do, for tests. */
    @NonNull
    Result getStripPending() {
        return mStripPending;
    }

    @Override
    protected void onDetachedFromWindow() {
        mSwipeDetector.cancel();
        stopRelease();
        super.onDetachedFromWindow();
    }

}
