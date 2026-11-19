package com.termux.app.terminal.io;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

/**
 * The pager of the terminal toolbar: the normal extra keys row on page 0 and the text input on
 * page 1. Dragging the row toward the text input and back is the pager's own, untouched.
 *
 * Additionally, a deliberate swipe over the normal row in the other direction, from left to right,
 * where the pager has nothing to scroll to, is reported to {@link Listener#onSwipeLeft()}. Only
 * then is the touch consumed: the keys and the pager get a cancel, so that no key is pressed, and
 * the rest of the touch is ignored. Everything else is passed on as is, so key presses, key
 * repeat on hold and popups keep working.
 */
public class ExtraKeysSwipeViewPager extends ViewPager {

    /** The minimum travel of the swipe, in dp. */
    static final float MIN_SWIPE_DISTANCE_DP = 48;

    /** The furthest the row slides along with a recognized swipe before it snaps back, in dp. */
    static final float FEEDBACK_PEAK_DP = 40;

    /** The duration of the slide out and snap back of a recognized swipe, before the system animation scale. */
    static final long FEEDBACK_DURATION_MS = 220;

    public interface Listener {
        default boolean isFeedbackEnabled() {
            return true;
        }

        /** A swipe was recognized over the normal extra keys row. */
        void onSwipeLeft();

        /**
         * Whether a swipe has an action right now. Asked for each touch: if {@code false}, the
         * swipe is not recognized at all, nothing is consumed and nothing is animated.
         */
        default boolean isEnabled() {
            return true;
        }
    }

    private final ExtraKeysSwipeDetector mSwipeDetector;
    private Listener mListener;

    /** Set when a swipe was recognized for the running touch, which is then swallowed. */
    private boolean mSwallowTouch;

    private ValueAnimator mFeedbackAnimator;

    public ExtraKeysSwipeViewPager(@NonNull Context context) {
        this(context, null);
    }

    public ExtraKeysSwipeViewPager(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mSwipeDetector = new ExtraKeysSwipeDetector(
            Math.max(MIN_SWIPE_DISTANCE_DP * density, 3f * touchSlop), touchSlop);
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mSwallowTouch = false;
                // Only on the settled normal row: not while the text input is selected or the
                // pager is still scrolling or settling on its way there or back.
                mSwipeDetector.onDown(event.getX(), event.getY(), mListener != null && mListener.isEnabled() && getCurrentItem() == 0 && getScrollX() == 0);
                break;

            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (mSwallowTouch) return true;
                if (mSwipeDetector.onMove(event.getX(), event.getY()) && getCurrentItem() == 0) {
                    mSwallowTouch = true;
                    cancelTouchOfChildren(event);
                    if (mListener != null) {
                        if (mListener.isFeedbackEnabled()) startSwipeFeedback();
                        mListener.onSwipeLeft();
                    }
                    return true;
                }
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                mSwipeDetector.cancel();
                break;

            case MotionEvent.ACTION_CANCEL:
                mSwipeDetector.cancel();
                if (mSwallowTouch) return true;
                break;

            default:
                break;
        }
        return super.dispatchTouchEvent(event);
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

    /**
     * Lets the row slide a little in the direction of the swipe and snap back, as feedback that
     * the swipe was taken. Skipped when the system animations are turned off. It always ends on
     * the rest position, also when cancelled by the next swipe or by the view going away.
     */
    private void startSwipeFeedback() {
        stopSwipeFeedback();
        if (!areSystemAnimationsEnabled()) return;

        final float peak = FEEDBACK_PEAK_DP * getResources().getDisplayMetrics().density;
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

    @Override
    protected void onDetachedFromWindow() {
        stopSwipeFeedback();
        super.onDetachedFromWindow();
    }

}
