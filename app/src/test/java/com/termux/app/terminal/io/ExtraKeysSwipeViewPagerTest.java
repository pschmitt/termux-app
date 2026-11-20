package com.termux.app.terminal.io;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class ExtraKeysSwipeViewPagerTest {

    /** Stands for an extra key: takes every touch and remembers what it was given. */
    private static final class RecordingView extends View {
        final List<Integer> actions = new ArrayList<>();

        RecordingView(Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            actions.add(event.getActionMasked());
            return true;
        }
    }

    private static final class TwoPageAdapter extends PagerAdapter {
        final RecordingView[] views;

        TwoPageAdapter(Context context) {
            views = new RecordingView[] {new RecordingView(context), new RecordingView(context)};
        }

        @Override public int getCount() { return 2; }
        @Override public boolean isViewFromObject(@NonNull View view, @NonNull Object object) { return view == object; }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            container.addView(views[position]);
            return views[position];
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }
    }

    private ExtraKeysSwipeViewPager mPager;
    private TwoPageAdapter mAdapter;
    private int mSwipes;
    private final List<String> mEvents = new ArrayList<>();
    private long mDownTime;

    @Before
    public void setUp() {
        // A ViewPager only populates its pages once it is attached to a window.
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Context context = activity;
        mPager = new ExtraKeysSwipeViewPager(context);
        mAdapter = new TwoPageAdapter(context);
        mPager.setAdapter(mAdapter);
        mSwipes = 0;
        mEvents.clear();
        mPager.setListener((direction, longSwipe) -> {
            mSwipes++;
            mEvents.add((longSwipe ? "long-" : "") + direction.name().toLowerCase(java.util.Locale.ROOT));
        });
        activity.setContentView(mPager, new ViewGroup.LayoutParams(1000, 150));
        layout();
    }

    private void layout() {
        mPager.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY));
        mPager.layout(0, 0, 1000, 150);
    }

    private boolean send(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) mDownTime = now;
        MotionEvent event = MotionEvent.obtain(mDownTime, now, action, x, y, 0);
        try {
            return mPager.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    @Test
    public void swipeAwayFromTheTextInputIsRecognizedOnceAndCancelsTheKey() {
        send(MotionEvent.ACTION_DOWN, 100, 75);
        send(MotionEvent.ACTION_MOVE, 120, 76);
        assertEquals(0, mSwipes);
        send(MotionEvent.ACTION_MOVE, 400, 78);
        assertEquals(1, mSwipes);
        send(MotionEvent.ACTION_MOVE, 600, 78);
        send(MotionEvent.ACTION_UP, 600, 78);
        assertEquals(1, mSwipes);

        // The key saw the press and then a cancel, never a release, so it presses nothing.
        List<Integer> seen = mAdapter.views[0].actions;
        assertEquals(MotionEvent.ACTION_DOWN, (int) seen.get(0));
        assertEquals(MotionEvent.ACTION_CANCEL, (int) seen.get(seen.size() - 1));
        assertFalse(seen.contains(MotionEvent.ACTION_UP));
        assertEquals(0, mPager.getCurrentItem());
    }

    @Test
    public void tapAndShortDragAreLeftToTheKey() {
        send(MotionEvent.ACTION_DOWN, 100, 75);
        send(MotionEvent.ACTION_UP, 100, 75);
        assertEquals(0, mSwipes);
        List<Integer> seen = mAdapter.views[0].actions;
        assertTrue(seen.contains(MotionEvent.ACTION_DOWN));
        assertTrue(seen.contains(MotionEvent.ACTION_UP));
        assertFalse(seen.contains(MotionEvent.ACTION_CANCEL));
    }

    @Test
    public void verticalDragIsLeftToTheKey() {
        send(MotionEvent.ACTION_DOWN, 100, 10);
        send(MotionEvent.ACTION_MOVE, 120, 140);
        send(MotionEvent.ACTION_UP, 120, 140);
        assertEquals(0, mSwipes);
    }

    @Test
    public void swipeTowardTheTextInputIsARightSwipeAndDoesNotPage() {
        send(MotionEvent.ACTION_DOWN, 800, 75);
        send(MotionEvent.ACTION_MOVE, 780, 75);
        send(MotionEvent.ACTION_MOVE, 500, 75);
        send(MotionEvent.ACTION_UP, 500, 75);
        assertEquals(List.of("right"), mEvents);
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
        assertEquals(0, mPager.getCurrentItem());
    }

    @Test
    public void withoutAnActionTowardTheTextInputThePagerStillDoesNotPage() {
        mPager.setListener(new ExtraKeysSwipeViewPager.Listener() {
            @Override public boolean hasSwipe(@NonNull ExtraKeysSwipeDetector.Direction direction) { return false; }
            @Override public void onSwipe(@NonNull ExtraKeysSwipeDetector.Direction direction, boolean longSwipe) { mSwipes++; }
        });
        send(MotionEvent.ACTION_DOWN, 800, 75);
        for (int x = 780; x >= 100; x -= 40) send(MotionEvent.ACTION_MOVE, x, 75);
        send(MotionEvent.ACTION_UP, 100, 75);
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
        assertEquals(0, mSwipes);
        assertEquals(0, mPager.getCurrentItem());
    }

    private void useLongSwipes() {
        mPager.setListener(new ExtraKeysSwipeViewPager.Listener() {
            @Override public boolean hasLongSwipe(@NonNull ExtraKeysSwipeDetector.Direction direction) { return true; }
            @Override public String getSwipeLabel(@NonNull ExtraKeysSwipeDetector.Direction direction, boolean longSwipe) {
                return longSwipe ? "New session" : "Next session";
            }
            @Override public void onSwipe(@NonNull ExtraKeysSwipeDetector.Direction direction, boolean longSwipe) {
                mSwipes++;
                mEvents.add((longSwipe ? "long-" : "") + direction.name().toLowerCase(java.util.Locale.ROOT));
            }
        });
    }

    @Test
    public void longSwipeIsFollowedWithAHintAndRunsOnRelease() {
        useLongSwipes();
        float density = mPager.getResources().getDisplayMetrics().density;
        float longDistance = ExtraKeysSwipeViewPager.LONG_SWIPE_DISTANCE_DP * density;
        send(MotionEvent.ACTION_DOWN, 50, 75);
        send(MotionEvent.ACTION_MOVE, 70, 75);
        send(MotionEvent.ACTION_MOVE, 50 + ExtraKeysSwipeViewPager.MIN_SWIPE_DISTANCE_DP * density + 10, 75);
        assertTrue(mPager.isHintShown());
        assertFalse(mPager.isHintLongReached());
        assertEquals("nothing runs before the release", 0, mSwipes);
        assertTrue("the row follows the finger", mPager.getChildAt(0).getTranslationX() > 0f);

        send(MotionEvent.ACTION_MOVE, 50 + longDistance + 10, 75);
        assertTrue(mPager.isHintLongReached());
        send(MotionEvent.ACTION_UP, 50 + longDistance + 10, 75);
        assertEquals(List.of("long-left"), mEvents);

        // The key was cancelled, never released.
        assertFalse(mAdapter.views[0].actions.contains(MotionEvent.ACTION_UP));
        mPager.getSwipeFeedbackAnimator().end();
        assertFalse(mPager.isHintShown());
        assertRestingAtZero();
    }

    @Test
    public void onlyTheShownRowSlidesNotTheTextInput() {
        useLongSwipes();
        float density = mPager.getResources().getDisplayMetrics().density;
        float x = 900 - ExtraKeysSwipeViewPager.MIN_SWIPE_DISTANCE_DP * density - 30;
        send(MotionEvent.ACTION_DOWN, 900, 75);
        send(MotionEvent.ACTION_MOVE, 880, 75);
        send(MotionEvent.ACTION_MOVE, x, 75);
        assertTrue("the row follows the finger to the left", mAdapter.views[0].getTranslationX() < 0f);
        assertEquals("the text input page stays off screen", 0f, mAdapter.views[1].getTranslationX(), 0f);
        send(MotionEvent.ACTION_UP, x, 75);
    }

    @Test
    public void shortReleaseOfAFollowedSwipeRunsTheNormalSwipe() {
        useLongSwipes();
        float density = mPager.getResources().getDisplayMetrics().density;
        float x = 900 - ExtraKeysSwipeViewPager.MIN_SWIPE_DISTANCE_DP * density - 10;
        send(MotionEvent.ACTION_DOWN, 900, 75);
        send(MotionEvent.ACTION_MOVE, 880, 75);
        send(MotionEvent.ACTION_MOVE, x, 75);
        send(MotionEvent.ACTION_UP, x, 75);
        assertEquals(List.of("right"), mEvents);
    }

    @Test
    public void followedSwipeBroughtBackOrCancelledRunsNothing() {
        useLongSwipes();
        float density = mPager.getResources().getDisplayMetrics().density;
        float x = 50 + ExtraKeysSwipeViewPager.MIN_SWIPE_DISTANCE_DP * density + 10;
        send(MotionEvent.ACTION_DOWN, 50, 75);
        send(MotionEvent.ACTION_MOVE, 70, 75);
        send(MotionEvent.ACTION_MOVE, x, 75);
        send(MotionEvent.ACTION_MOVE, 55, 75);
        send(MotionEvent.ACTION_UP, 55, 75);

        send(MotionEvent.ACTION_DOWN, 50, 75);
        send(MotionEvent.ACTION_MOVE, 70, 75);
        send(MotionEvent.ACTION_MOVE, x, 75);
        send(MotionEvent.ACTION_CANCEL, x, 75);
        assertEquals(0, mSwipes);
    }

    @Test
    public void swipeIsNotRecognizedOnTheTextInputPage() {
        mPager.setCurrentItem(1, false);
        layout();
        send(MotionEvent.ACTION_DOWN, 100, 75);
        send(MotionEvent.ACTION_MOVE, 400, 75);
        send(MotionEvent.ACTION_UP, 400, 75);
        assertEquals(0, mSwipes);
    }

    @Test
    public void nothingIsSwallowedWithoutAListener() {
        mPager.setListener(null);
        send(MotionEvent.ACTION_DOWN, 100, 75);
        send(MotionEvent.ACTION_MOVE, 400, 75);
        send(MotionEvent.ACTION_UP, 400, 75);
        assertEquals(0, mSwipes);
        // The stock ViewPager may cancel the key itself for a drag; what matters is that it stays on page 0.
        assertEquals(0, mPager.getCurrentItem());
    }

    private void swipe(float fromX, float toX) {
        send(MotionEvent.ACTION_DOWN, fromX, 75);
        send(MotionEvent.ACTION_MOVE, fromX + 30, 75);
        send(MotionEvent.ACTION_MOVE, toX, 75);
        send(MotionEvent.ACTION_UP, toX, 75);
    }

    /** Advances the clock in frame sized steps, so the animator gets frames spread over its duration. */
    private void advance(long millis) {
        for (long t = 0; t < millis; t += 16) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
    }

    private void assertRestingAtZero() {
        for (int i = 0; i < mPager.getChildCount(); i++)
            assertEquals(0f, mPager.getChildAt(i).getTranslationX(), 0f);
    }

    @Test
    public void recognizedSwipeSlidesTheRowAndEndsAtRest() {
        swipe(100, 400);
        assertEquals(1, mSwipes);
        assertTrue(mPager.isSwipeFeedbackRunning());

        // Seek instead of waiting: Robolectric's frame clock does not pace animators reliably.
        android.animation.ValueAnimator animator = mPager.getSwipeFeedbackAnimator();
        animator.setCurrentPlayTime(ExtraKeysSwipeViewPager.FEEDBACK_DURATION_MS / 2);
        assertTrue("the row moved to the right", mPager.getChildAt(0).getTranslationX() > 0f);
        animator.end();
        advance(500);
        assertFalse(mPager.isSwipeFeedbackRunning());
        assertRestingAtZero();
    }

    @Test
    public void repeatedSwipesDoNotLeaveTheRowDisplaced() {
        swipe(100, 400);
        advance(60);
        swipe(100, 400);
        assertEquals(2, mSwipes);
        advance(500);
        assertFalse(mPager.isSwipeFeedbackRunning());
        assertRestingAtZero();
    }

    @Test
    public void goingAwayMidAnimationLeavesTheRowAtRest() {
        swipe(100, 400);
        advance(60);
        ((ViewGroup) mPager.getParent()).removeView(mPager);
        assertFalse(mPager.isSwipeFeedbackRunning());
        assertRestingAtZero();
    }

    @Test
    public void noAnimationWhenTheSystemAnimationsAreOff() {
        Settings.Global.putFloat(mPager.getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
        swipe(100, 400);
        assertEquals("the action still runs", 1, mSwipes);
        assertFalse(mPager.isSwipeFeedbackRunning());
        assertRestingAtZero();
    }

    @Test
    public void withoutAnActionTheSwipeIsNotRecognizedAnimatedOrConsumed() {
        mPager.setListener(new ExtraKeysSwipeViewPager.Listener() {
            @Override public boolean hasSwipe(@NonNull ExtraKeysSwipeDetector.Direction direction) { return false; }
            @Override public void onSwipe(@NonNull ExtraKeysSwipeDetector.Direction direction, boolean longSwipe) { mSwipes++; }
        });
        swipe(100, 400);
        assertEquals(0, mSwipes);
        assertFalse(mPager.isSwipeFeedbackRunning());
        assertRestingAtZero();
        // The key got the touch like with a stock pager: it was pressed and not cancelled by us at the start.
        assertEquals(MotionEvent.ACTION_DOWN, (int) mAdapter.views[0].actions.get(0));
    }

    @Test
    public void eachTouchIsRecognizedOnItsOwn() {
        for (int i = 0; i < 2; i++) {
            send(MotionEvent.ACTION_DOWN, 100, 75);
            send(MotionEvent.ACTION_MOVE, 400, 75);
            send(MotionEvent.ACTION_UP, 400, 75);
        }
        assertEquals(2, mSwipes);
    }

}
