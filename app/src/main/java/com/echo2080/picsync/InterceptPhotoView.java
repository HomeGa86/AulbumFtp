package com.echo2080.picsync;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

public class InterceptPhotoView extends com.github.chrisbanes.photoview.PhotoView {
    public InterceptPhotoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getPointerCount() > 1 && getScale() > 1.05f) {
            // When zoomed and using multi-touch, prevent ViewPager2 from swiping
            getParent().requestDisallowInterceptTouchEvent(true);
        } else if (ev.getPointerCount() > 1) {
            // Even if not zoomed, prevent swipe during the initial pinch gesture
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return super.dispatchTouchEvent(ev);
    }
}