package com.termux.app.terminal.io;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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
 * Deliberate horizontal swipes over the normal row are reported to {@link Listener#onSwipe}, in
 * both directions: the pager itself never drags from the normal row to the text input, that is
 * the {@code toggle-text-input} action that a swipe or any other gesture can be bound to. From the
 * text input, dragging back to the normal row is the pager's own, untouched.
 *
 * Once a swipe is recognized, the touch is consumed: the keys get a cancel, so that no key is
 * pressed, and the rest of the touch belongs to the swipe. Everything else is passed on as is, so
 * key presses, key repeat on hold and popups keep working.
 *
 * A direction with a long swipe is followed until the finger goes up: the row slides along with
 * the finger and a hint names the action that releasing runs, filling up on the way to the long
 * swipe and turning to the long swipe's action, with a tick, once it is reached. Going back to
 * where the swipe started calls it off.
 */
public class ExtraKeysSwipeViewPager extends ViewPager {

    /** The minimum travel of a swipe, in dp. */
    static final float MIN_SWIPE_DISTANCE_DP = 48;

    /** The minimum travel of a long swipe, in dp. */
    static final float LONG_SWIPE_DISTANCE_DP = 140;

    /** The furthest the row slides along with a recognized swipe before it snaps back, in dp. */
    static final float FEEDBACK_PEAK_DP = 40;

    /** The furthest the row follows the finger during a followed swipe, in dp. */
    static final float FOLLOW_PEAK_DP = 56;

    /** The duration of the slide out and snap back of a recognized swipe, before the system animation scale. */
    static final long FEEDBACK_DURATION_MS = 220;

    /** The duration of the snap back and fade out at the end of a followed swipe. */
    static final long RELEASE_DURATION_MS = 200;

    /** The duration of the hint fading in when a followed swipe is recognized. */
    static final long HINT_FADE_IN_MS = 120;

    /* The toolbar is always black: TermuxPP's lavender on a dark plum pill. */
    private static final int HINT_SCRIM_COLOR = 0x8C000000;
    private static final int HINT_PILL_COLOR = 0xF0261E33;
    private static final int HINT_ACCENT_COLOR = 0xFFD8B4FE;
    private static final int HINT_PROGRESS_COLOR = 0x5CD8B4FE;
    private static final int HINT_TEXT_COLOR = 0xFFFFFFFF;
    private static final int HINT_ON_ACCENT_COLOR = 0xFF2A0F55;

    public interface Listener extends ExtraKeysSwipeDetector.Bindings {

        /** A swipe over the normal extra keys row is done. */
        void onSwipe(@NonNull Direction direction, boolean longSwipe);

        /**
         * Whether a swipe has an action right now. Asked for each touch: if {@code false}, the
         * swipe is not recognized at all, nothing is consumed and nothing is animated.
         */
        @Override
        default boolean hasSwipe(@NonNull Direction direction) {
            return true;
        }

        /** Whether the direction has a long swipe right now, which makes the swipe followed. */
        @Override
        default boolean hasLongSwipe(@NonNull Direction direction) {
            return false;
        }

        /** The name of the action of a swipe for the hint, or {@code null} if it has none. */
        @Nullable
        default String getSwipeLabel(@NonNull Direction direction, boolean longSwipe) {
            return null;
        }

        /** Whether the row may slide as feedback. The hint of a followed swipe is always shown. */
        default boolean isFeedbackEnabled() {
            return true;
        }
    }

    private final ExtraKeysSwipeDetector mSwipeDetector;
    private Listener mListener;

    /** Set when a swipe was recognized for the running touch, which is then swallowed. */
    private boolean mSwallowTouch;

    /** Set when the running touch started on the settled normal row, where the pager may not drag. */
    private boolean mPagingBlocked;

    private ValueAnimator mFeedbackAnimator;

    /* The hint of a followed swipe. */
    private final Paint mScrimPaint = new Paint();
    private final Paint mPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSubLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mPillRect = new RectF();
    private final float mDensity;
    @Nullable private Direction mHintDirection;
    @Nullable private String mHintLabel;
    @Nullable private String mHintLongLabel;
    private float mHintAlpha;
    private float mHintProgress;
    private boolean mHintLongReached;
    private boolean mHintCalledOff;
    private ValueAnimator mHintFadeInAnimator;

    public ExtraKeysSwipeViewPager(@NonNull Context context) {
        this(context, null);
    }

    public ExtraKeysSwipeViewPager(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        float minDistance = Math.max(MIN_SWIPE_DISTANCE_DP * mDensity, 3f * touchSlop);
        mSwipeDetector = new ExtraKeysSwipeDetector(minDistance,
            Math.max(LONG_SWIPE_DISTANCE_DP * mDensity, 2.5f * minDistance), touchSlop);

        mScrimPaint.setColor(HINT_SCRIM_COLOR);
        mLabelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mLabelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
            context.getResources().getDisplayMetrics()));
        mLabelPaint.setTextAlign(Paint.Align.CENTER);
        mSubLabelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11,
            context.getResources().getDisplayMetrics()));
        mSubLabelPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
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
                if (handleResult(mSwipeDetector.onMove(event.getX(), event.getY()), event)) return true;
                break;

            case MotionEvent.ACTION_UP:
                if (mSwipeDetector.isFollowing()) {
                    finishFollow(mSwipeDetector.onUp(event.getX(), event.getY()));
                    return true;
                }
                if (mSwallowTouch) return true;
                if (handleResult(mSwipeDetector.onUp(event.getX(), event.getY()), event)) return true;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (mSwipeDetector.isFollowing()) {
                    mSwipeDetector.cancel();
                    finishFollow(Result.NONE);
                    return true;
                }
                mSwipeDetector.cancel();
                break;

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

    /** @return Whether the event was taken by the swipe. */
    private boolean handleResult(@NonNull Result result, @NonNull MotionEvent event) {
        if (result == Result.NONE || getCurrentItem() != 0) return false;
        mSwallowTouch = true;
        cancelTouchOfChildren(event);
        Direction direction = mSwipeDetector.getDirection();
        if (direction == null) return true;

        if (result == Result.RECOGNIZED) {
            startFollow(direction);
        } else if (mListener != null) {
            if (mListener.isFeedbackEnabled()) startSwipeFeedback(direction);
            mListener.onSwipe(direction, result == Result.LONG_SWIPE);
        }
        return true;
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

    private void startFollow(@NonNull Direction direction) {
        stopSwipeFeedback();
        mHintDirection = direction;
        mHintLabel = mListener == null ? null : mListener.getSwipeLabel(direction, false);
        mHintLongLabel = mListener == null ? null : mListener.getSwipeLabel(direction, true);
        mHintLongReached = false;
        mHintCalledOff = false;
        mHintProgress = 0f;

        if (areSystemAnimationsEnabled()) {
            final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(HINT_FADE_IN_MS);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a -> {
                mHintAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            mHintFadeInAnimator = animator;
            animator.start();
        } else {
            mHintAlpha = 1f;
        }
        updateFollow();
    }

    private void updateFollow() {
        if (mHintDirection == null) return;
        boolean longReached = mSwipeDetector.isLongReached();
        if (longReached != mHintLongReached) {
            mHintLongReached = longReached;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        mHintCalledOff = mSwipeDetector.isCalledOff();
        mHintProgress = mSwipeDetector.getLongProgress();

        if (mListener != null && mListener.isFeedbackEnabled()) {
            setPagesTranslationX(mHintDirection.sign() * followTranslation(mSwipeDetector.getDistance()));
        }
        invalidate();
    }

    /** How far the row follows a finger that is {@code distance} away: along at first, then less and less. */
    private float followTranslation(float distance) {
        if (distance <= 0) return 0f;
        float peak = FOLLOW_PEAK_DP * mDensity;
        return peak * (1f - (float) Math.exp(-distance / (2f * peak)));
    }

    private void finishFollow(@NonNull Result result) {
        final Direction direction = mHintDirection;
        if (mHintFadeInAnimator != null) {
            mHintFadeInAnimator.cancel();
            mHintFadeInAnimator = null;
        }
        if (result == Result.LONG_SWIPE || result == Result.SWIPE) {
            // Show what was taken while the hint fades out.
            mHintLongReached = result == Result.LONG_SWIPE;
            mHintCalledOff = false;
        }
        startReleaseAnimation();
        if (direction != null && mListener != null && (result == Result.SWIPE || result == Result.LONG_SWIPE)) {
            mListener.onSwipe(direction, result == Result.LONG_SWIPE);
        }
    }

    /** Lets the row snap back and the hint fade out, from wherever they are. */
    private void startReleaseAnimation() {
        ValueAnimator previous = mFeedbackAnimator;
        mFeedbackAnimator = null;
        if (previous != null) previous.cancel();

        final float startTranslation = getChildCount() > 0 ? getChildAt(0).getTranslationX() : 0f;
        final float startAlpha = mHintAlpha;
        if (!areSystemAnimationsEnabled()) {
            clearHint();
            setPagesTranslationX(0f);
            return;
        }

        final ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
        animator.setDuration(RELEASE_DURATION_MS);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(a -> {
            float remaining = (float) a.getAnimatedValue();
            setPagesTranslationX(startTranslation * remaining);
            mHintAlpha = startAlpha * remaining;
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (mFeedbackAnimator == animator) {
                    mFeedbackAnimator = null;
                    clearHint();
                    setPagesTranslationX(0f);
                }
            }
        });
        mFeedbackAnimator = animator;
        animator.start();
    }

    private void clearHint() {
        mHintDirection = null;
        mHintLabel = null;
        mHintLongLabel = null;
        mHintAlpha = 0f;
        mHintProgress = 0f;
        mHintLongReached = false;
        mHintCalledOff = false;
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (mHintDirection != null && mHintAlpha > 0f) drawHint(canvas);
    }

    /**
     * A scrim over the row and a pill in its middle that names the action that releasing runs:
     * the swipe's, filling up toward the long swipe, then the long swipe's in the accent color.
     */
    private void drawHint(@NonNull Canvas canvas) {
        final Direction direction = mHintDirection;
        if (direction == null) return;
        final int alpha = Math.round(255 * Math.max(0f, Math.min(1f, mHintAlpha)));
        final float left = getScrollX();
        final float width = getWidth();
        final float height = getHeight();

        mScrimPaint.setAlpha(Math.round(Color.alpha(HINT_SCRIM_COLOR) * alpha / 255f));
        canvas.drawRect(left, 0, left + width, height, mScrimPaint);

        final boolean longReached = mHintLongReached;
        final boolean calledOff = mHintCalledOff;
        String label = calledOff ? "Cancel" : longReached ? mHintLongLabel : mHintLabel;
        if (label == null) label = longReached ? "" : "Keep going";
        final String arrow = longReached ? "»" : "›";
        final String text = calledOff ? label
            : direction == Direction.LEFT ? label + "  " + arrow : arrowFor(arrow) + "  " + label;
        // Tell what lies further, while it is not reached yet.
        final String subText = !calledOff && !longReached && mHintLongLabel != null
            ? "further: " + mHintLongLabel : null;

        final float padH = 16 * mDensity;
        final float padV = 6 * mDensity;
        final float gap = 2 * mDensity;
        final Paint.FontMetrics labelMetrics = mLabelPaint.getFontMetrics();
        final Paint.FontMetrics subMetrics = mSubLabelPaint.getFontMetrics();
        final float labelHeight = labelMetrics.descent - labelMetrics.ascent;
        final float subHeight = subMetrics.descent - subMetrics.ascent;
        final boolean showSub = subText != null && labelHeight + gap + subHeight + 2 * padV <= height - 4 * mDensity;
        final float contentHeight = labelHeight + (showSub ? gap + subHeight : 0);
        float contentWidth = mLabelPaint.measureText(text);
        if (showSub) contentWidth = Math.max(contentWidth, mSubLabelPaint.measureText(subText));

        final float pillWidth = Math.min(width - 16 * mDensity, contentWidth + 2 * padH);
        final float pillHeight = Math.min(height - 4 * mDensity, contentHeight + 2 * padV);
        final float cx = left + width / 2f;
        final float cy = height / 2f;
        mPillRect.set(cx - pillWidth / 2f, cy - pillHeight / 2f, cx + pillWidth / 2f, cy + pillHeight / 2f);
        final float radius = pillHeight / 2f;

        // The pill, then its fill toward the long swipe from the side the finger comes from.
        mPillPaint.setColor(longReached ? HINT_ACCENT_COLOR : HINT_PILL_COLOR);
        mPillPaint.setAlpha(Math.round(Color.alpha(mPillPaint.getColor()) * alpha / 255f));
        canvas.drawRoundRect(mPillRect, radius, radius, mPillPaint);
        if (!longReached && !calledOff && mHintLongLabel != null && mHintProgress > 0f) {
            canvas.save();
            float fill = pillWidth * mHintProgress;
            if (direction == Direction.LEFT)
                canvas.clipRect(mPillRect.left, mPillRect.top, mPillRect.left + fill, mPillRect.bottom);
            else
                canvas.clipRect(mPillRect.right - fill, mPillRect.top, mPillRect.right, mPillRect.bottom);
            mPillPaint.setColor(HINT_PROGRESS_COLOR);
            mPillPaint.setAlpha(Math.round(Color.alpha(HINT_PROGRESS_COLOR) * alpha / 255f));
            canvas.drawRoundRect(mPillRect, radius, radius, mPillPaint);
            canvas.restore();
        }

        final int textColor = longReached ? HINT_ON_ACCENT_COLOR : HINT_TEXT_COLOR;
        mLabelPaint.setColor(textColor);
        mLabelPaint.setAlpha(Math.round((calledOff ? 0.6f : 1f) * alpha));
        float baseline = cy - contentHeight / 2f - labelMetrics.ascent;
        canvas.drawText(text, cx, baseline, mLabelPaint);
        if (showSub) {
            mSubLabelPaint.setColor(HINT_TEXT_COLOR);
            mSubLabelPaint.setAlpha(Math.round(0.7f * alpha));
            canvas.drawText(subText, cx, baseline + labelMetrics.descent + gap - subMetrics.ascent, mSubLabelPaint);
        }
    }

    private static String arrowFor(String arrow) {
        return "»".equals(arrow) ? "«" : "‹";
    }



    /* The feedback of an immediate swipe. */

    /**
     * Lets the row slide a little in the direction of the swipe and snap back, as feedback that
     * the swipe was taken. Skipped when the system animations are turned off. It always ends on
     * the rest position, also when cancelled by the next swipe or by the view going away.
     */
    private void startSwipeFeedback(@NonNull Direction direction) {
        stopSwipeFeedback();
        if (!areSystemAnimationsEnabled()) return;

        final float peak = direction.sign() * FEEDBACK_PEAK_DP * mDensity;
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(FEEDBACK_DURATION_MS);
        animator.addUpdateListener(a ->
            setPagesTranslationX(peak * (float) Math.sin(Math.PI * (float) a.getAnimatedValue())));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                setPagesTranslationX(0f);
                if (mFeedbackAnimator == animator) mFeedbackAnimator = null;
            }
        });
        mFeedbackAnimator = animator;
        animator.start();
    }

    private void stopSwipeFeedback() {
        ValueAnimator animator = mFeedbackAnimator;
        mFeedbackAnimator = null;
        if (animator != null) animator.cancel(); // ends on the rest position
        if (mHintFadeInAnimator != null) {
            mHintFadeInAnimator.cancel();
            mHintFadeInAnimator = null;
        }
        clearHint();
        setPagesTranslationX(0f);
    }

    private void setPagesTranslationX(float translationX) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getTranslationX() != translationX) child.setTranslationX(translationX);
        }
    }

    private boolean areSystemAnimationsEnabled() {
        return Settings.Global.getFloat(getContext().getContentResolver(),
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    /** The running feedback animation or null, for tests. */
    ValueAnimator getSwipeFeedbackAnimator() {
        return mFeedbackAnimator;
    }

    /** Whether the feedback is running, for tests. */
    boolean isSwipeFeedbackRunning() {
        return mFeedbackAnimator != null;
    }

    /** Whether the hint of a followed swipe is shown, for tests. */
    boolean isHintShown() {
        return mHintDirection != null;
    }

    /** Whether the hint shows the long swipe as reached, for tests. */
    boolean isHintLongReached() {
        return mHintLongReached;
    }

    @Override
    protected void onDetachedFromWindow() {
        mSwipeDetector.cancel();
        stopSwipeFeedback();
        super.onDetachedFromWindow();
    }

}
