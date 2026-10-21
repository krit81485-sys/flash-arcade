package com.flasharcade.launcher.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Keeps the button children interactive while forwarding transparent-area
 * gestures to the game WebView beneath them.
 */
public final class TouchPassthroughFrameLayout extends FrameLayout {
    private View forwardingTarget;

    public TouchPassthroughFrameLayout(Context context) {
        super(context);
        initialize();
    }

    public TouchPassthroughFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public TouchPassthroughFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    public void setForwardingTarget(View target) {
        forwardingTarget = target;
    }

    private void initialize() {
        setClipChildren(false);
        setClipToPadding(false);
        setClickable(false);
        setFocusable(false);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // Direction/action buttons consume their own events. Empty transparent
        // regions are forwarded so WebView/Ruffle receives canvas taps and drags.
        if (super.dispatchTouchEvent(event)) {
            return true;
        }
        if (forwardingTarget == null) {
            return false;
        }
        MotionEvent forwarded = MotionEvent.obtain(event);
        try {
            return forwardingTarget.dispatchTouchEvent(forwarded);
        } finally {
            forwarded.recycle();
        }
    }
}
