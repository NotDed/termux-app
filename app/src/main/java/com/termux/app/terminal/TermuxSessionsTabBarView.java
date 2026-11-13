package com.termux.app.terminal;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * Horizontal bar hosting the session tabs. Taps are passed through to the tabs, but a horizontal
 * swipe is intercepted (so that the tab under the finger is not clicked) and reported via
 * {@link SwipeListener}.
 */
public class TermuxSessionsTabBarView extends LinearLayout {

    public interface SwipeListener {
        /** @param forward {@code true} for a right-to-left swipe (next session). */
        void onSwipe(boolean forward);
    }

    private final int mTouchSlop;
    private final float mSwipeThreshold;
    private float mDownX, mDownY;
    private boolean mSwiping;
    @Nullable private SwipeListener mSwipeListener;

    public TermuxSessionsTabBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mSwipeThreshold = 40 * context.getResources().getDisplayMetrics().density;
    }

    public void setSwipeListener(@Nullable SwipeListener listener) {
        mSwipeListener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getX();
                mDownY = ev.getY();
                mSwiping = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(ev.getX() - mDownX);
                float dy = Math.abs(ev.getY() - mDownY);
                if (dx > mTouchSlop && dx > dy) {
                    mSwiping = true;
                    // Stop children from treating this as a long press
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getX();
                mDownY = ev.getY();
                mSwiping = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!mSwiping && Math.abs(ev.getX() - mDownX) > mTouchSlop)
                    mSwiping = true;
                return true;
            case MotionEvent.ACTION_UP:
                float dx = ev.getX() - mDownX;
                if (mSwiping && Math.abs(dx) >= mSwipeThreshold && mSwipeListener != null)
                    mSwipeListener.onSwipe(dx < 0);
                mSwiping = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                mSwiping = false;
                return true;
        }
        return super.onTouchEvent(ev);
    }

}
