package com.qozix.tileview.widgets;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.support.annotation.IdRes;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.EdgeEffectCompat;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.Interpolator;
import android.widget.Scroller;

import com.qozix.tileview.geom.FloatMathHelper;
import com.qozix.tileview.view.TouchUpGestureDetector;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * ZoomPanLayout extends ViewGroup to provide support for scrolling and zooming.
 * Fling, drag, pinch and double-tap events are supported natively.
 *
 * Children of ZoomPanLayout are laid out to the sizes provided by setSize,
 * and will always be positioned at 0,0.
 */

public class ZoomPanLayout extends ViewGroup implements
  GestureDetector.OnGestureListener,
  GestureDetector.OnDoubleTapListener,
  ScaleGestureDetector.OnScaleGestureListener,
  TouchUpGestureDetector.OnTouchUpListener {

  private static final int DEFAULT_ZOOM_PAN_ANIMATION_DURATION = 400;
  public static final int INVALID_POINTER = -1;
  private static final String TAG = ZoomPanLayout.class.getSimpleName();

  private int mBaseWidth;
  private int mBaseHeight;
  private int mScaledWidth;
  private int mScaledHeight;

  private float mScale = 1;

  private float mMinScale = 1;
  private float mMaxScale = 1;

  private float mEffectiveMinScale = 0f;
  private boolean mShouldScaleToFit = true;
  private boolean mShouldLoopScale = true;

  private boolean mIsFlinging;
  private boolean mIsDragging;
  private boolean mIsScaling;
  private boolean mIsSliding;

  private int mAnimationDuration = DEFAULT_ZOOM_PAN_ANIMATION_DURATION;

  private List<ZoomListener> mZoomListeners = new ArrayList<>();
  private List<PanListener> mPanListeners = new ArrayList<>();

  private Scroller mScroller;
  private ZoomPanAnimator mZoomPanAnimator;

  private ScaleGestureDetector mScaleGestureDetector;
  private GestureDetector mGestureDetector;
  private TouchUpGestureDetector mTouchUpGestureDetector;
  private float mStartY = 0f;
  private float mStartX = 0f;
  private double mMoveThreshold;
  private List<Integer> idsImmuneToScale;
  private boolean resetScaleToFit = true;
  private boolean doubleTap = false;

  private EdgeGlowEffect edgeGlowEffect;
  private int edgeGlowEffectColor = android.R.color.white;
  private float mScaleFactor = 1;
  private InternalZoomListener mInternalZoomListener = new InternalZoomListener();

  public interface OnScrollChangeListener {
    void onScrollChanged(int scrollX, int scrollY);
  }

  private OnScrollChangeListener onScrollChangeListener;

  /**
   * Constructor to use when creating a ZoomPanLayout from code.
   *
   * @param context The Context the ZoomPanLayout is running in, through which it can access the current theme, resources, etc.
   */
  public ZoomPanLayout(Context context) {
    this(context, null);
  }

  public ZoomPanLayout(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public ZoomPanLayout(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    addZoomListener(mInternalZoomListener);
    edgeGlowEffect = new EdgeGlowEffect(context);

    idsImmuneToScale = new ArrayList<>();

    mMoveThreshold = context.getResources().getDisplayMetrics().density * 12;

    setWillNotDraw(false);
    mScroller = new Scroller(context);
    mGestureDetector = new GestureDetector(context, this);
    mScaleGestureDetector = new ScaleGestureDetector(context, this);
    mTouchUpGestureDetector = new TouchUpGestureDetector(this);
  }

  public void reset() {
    mBaseWidth = 0;
    mBaseHeight = 0;
    mScaledWidth = 0;
    mScaledHeight = 0;

    mScale = 1;
    mMinScale = 1;
    mMaxScale = 1;

    mEffectiveMinScale = 0f;
    mScaleFactor = 1;
    invalidate();
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    // the container's children should be the size provided by setSize
    // don't use measureChildren because that grabs the child's LayoutParams
    int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(mBaseWidth, MeasureSpec.EXACTLY);
    int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(mBaseHeight, MeasureSpec.EXACTLY);
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
    }
    // but the layout itself should report normal (on screen) dimensions
    int width = MeasureSpec.getSize(widthMeasureSpec);
    int height = MeasureSpec.getSize(heightMeasureSpec);
    width = resolveSize(width, widthMeasureSpec);
    height = resolveSize(height, heightMeasureSpec);
    setMeasuredDimension(width, height);
  }

  /*
  ZoomPanChildren will always be laid out with the scaled dimenions - what is visible during
  scroll operations.  Thus, a RelativeLayout added as a child that had views within it using
  rules like ALIGN_PARENT_RIGHT would function as expected; similarly, an ImageView would be
  stretched between the visible edges.
  If children further operate on scale values, that should be accounted for
  in the child's logic (see ScalingLayout).
   */
  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    for (int i = 0; i < getChildCount(); i++) {
      View child = getChildAt(i);
      if (child.getVisibility() != GONE) {
        scaleChildren();
        child.layout(0, 0, mBaseWidth, mBaseHeight);
      }
    }

    if (doubleTap || mScale > mMinScale) {
      setScale(mScale);
    } else {
      calculateMinimumScaleToFit();
      mMaxScale = mScaleFactor * mMinScale;
      setScale(mEffectiveMinScale);
    }

    for (ZoomListener listener : mZoomListeners) {
      listener.onZoomUpdate(mScale, Origination.PINCH);
    }
    constrainScrollToLimits();
  }

  /**
   * Determines whether the ZoomPanLayout should limit it's minimum scale to no less than what
   * would be required to fill it's container.
   *
   * @param shouldScaleToFit True to limit minimum scale, false to allow arbitrary minimum scale.
   */
  public void setShouldScaleToFit(boolean shouldScaleToFit) {
    mShouldScaleToFit = shouldScaleToFit;
    calculateMinimumScaleToFit();
  }

  /**
   * Determines whether the ZoomPanLayout should go back to minimum scale after a double-tap at
   * maximum scale.
   *
   * @param shouldLoopScale True to allow going back to minimum scale, false otherwise.
   */
  public void setShouldLoopScale(boolean shouldLoopScale) {
    mShouldLoopScale = shouldLoopScale;
  }

  /**
   * Set minimum and maximum mScale values for this ZoomPanLayout.
   * Note that if shouldScaleToFit is set to true, the minimum value set here will be ignored
   * Default values are 0 and 1.
   *
   * @param min Minimum scale the ZoomPanLayout should accept.
   * @param max Maximum scale the ZoomPanLayout should accept.
   */
  public void setScaleLimits(float min, float max) {
    mMinScale = min;
    mMaxScale = max;
    mScale = min;
    setScale(mScale);
  }

  /**
   * Sets the size (width and height) of the ZoomPanLayout
   * as it should be rendered at a scale of 1f (100%).
   *
   * @param width  Width of the underlying image, not the view or viewport.
   * @param height Height of the underlying image, not the view or viewport.
   */
  public void setSize(int width, int height) {
    mBaseWidth = width;
    mBaseHeight = height;
    updateScaledDimensions();
    requestLayout();
  }

  /**
   * Returns the base (not scaled) width of the underlying composite image.
   *
   * @return The base (not scaled) width of the underlying composite image.
   */
  public int getBaseWidth() {
    return mBaseWidth;
  }

  /**
   * Returns the base (not scaled) height of the underlying composite image.
   *
   * @return The base (not scaled) height of the underlying composite image.
   */
  public int getBaseHeight() {
    return mBaseHeight;
  }

  /**
   * Returns the scaled width of the underlying composite image.
   *
   * @return The scaled width of the underlying composite image.
   */
  public int getScaledWidth() {
    return mScaledWidth;
  }

  /**
   * Returns the scaled height of the underlying composite image.
   *
   * @return The scaled height of the underlying composite image.
   */
  public int getScaledHeight() {
    return mScaledHeight;
  }

  /**
   * Sets the scale (0-1) of the ZoomPanLayout.
   *
   * @param scale The new value of the ZoomPanLayout scale.
   */
  public void setScale(float scale) {
    scale = getConstrainedDestinationScale(scale);

    if (mScale != scale) {
      float previous = mScale;
      mScale = scale;
      updateScaledDimensions();
      constrainScrollToLimits();
      onScaleChanged(scale, previous);
      scaleChildren();
      invalidate();
    }
  }

  private void scaleChildren() {
    for (int i = getChildCount() - 1; i >= 0; i--) {
      View child = getChildAt(i);
      child.setPivotX(0);
      child.setPivotY(0);
      child.setScaleX(mScale);
      child.setScaleY(mScale);
      if (!idsImmuneToScale.isEmpty()) {
        unscaleViewsWithImmunity(child);
      }
    }
  }

  public void resetZoomOnFocalPoint(int focusX, int focusY) {
    smoothScaleFromFocalPoint(focusX, focusY, mEffectiveMinScale);
  }

  private View unscaleViewsWithImmunity(View view) {
    ViewGroup viewGroup;
    if (view instanceof ViewGroup) {
      viewGroup = (ViewGroup) view;
      for (int i = 0; i < viewGroup.getChildCount(); i++) {
        View v = unscaleViewsWithImmunity(viewGroup.getChildAt(i));
        if (v != null) {
          unscale(v);
        }
      }
    }
    if (idsImmuneToScale.contains(view.getId())) {
      return view;
    }
    return null;
  }

  private void unscale(View view) {
    view.setPivotX(view.getWidth() / 2);
    view.setPivotY(view.getHeight() / 2);
    view.setScaleX(1 / mScale);
    view.setScaleY(1 / mScale);
  }

  /**
   * Retrieves the current scale of the ZoomPanLayout.
   *
   * @return The current scale of the ZoomPanLayout.
   */
  public float getScale() {
    return mScale;
  }

  /**
   * Returns whether the ZoomPanLayout is currently being flung.
   *
   * @return true if the ZoomPanLayout is currently flinging, false otherwise.
   */
  public boolean isFlinging() {
    return mIsFlinging;
  }

  /**
   * Returns whether the ZoomPanLayout is currently being dragged.
   *
   * @return true if the ZoomPanLayout is currently dragging, false otherwise.
   */
  public boolean isDragging() {
    return mIsDragging;
  }

  /**
   * Returns whether the ZoomPanLayout is currently operating a scroll tween.
   *
   * @return True if the ZoomPanLayout is currently scrolling, false otherwise.
   */
  public boolean isSliding() {
    return mIsSliding;
  }

  /**
   * Returns whether the ZoomPanLayout is currently operating a scale tween.
   *
   * @return True if the ZoomPanLayout is currently scaling, false otherwise.
   */
  public boolean isScaling() {
    return mIsScaling;
  }

  /**
   * Returns the Scroller instance used to manage dragging and flinging.
   *
   * @return The Scroller instance use to manage dragging and flinging.
   */
  public Scroller getScroller() {
    return mScroller;
  }

  /**
   * Returns the duration zoom and pan animations will use.
   *
   * @return The duration zoom and pan animations will use.
   */
  public int getAnimationDuration() {
    return mAnimationDuration;
  }

  public boolean isZoomed() {
    return mScale > mEffectiveMinScale;
  }

  /**
   * Set the duration zoom and pan animation will use.
   *
   * @param animationDuration The duration animations will use.
   */
  public void setAnimationDuration(int animationDuration) {
    mAnimationDuration = animationDuration;
    if (mZoomPanAnimator != null) {
      mZoomPanAnimator.setDuration(mAnimationDuration);
    }
  }

  public boolean addZoomListener(ZoomListener zoomListener) {
    return mZoomListeners.add(zoomListener);
  }

  public boolean addPanListener(PanListener panListener) {
    return mPanListeners.add(panListener);
  }

  public boolean removeZoomListener(ZoomListener listener) {
    return mZoomListeners.remove(listener);
  }

  public boolean removePanListener(PanListener listener) {
    return mPanListeners.remove(listener);
  }

  /**
   * Scrolls and centers the ZoomPanLayout to the x and y values provided.
   *
   * @param x Horizontal destination point.
   * @param y Vertical destination point.
   */
  public void scrollToAndCenter(int x, int y) {
    scrollTo(x - getHalfWidth(), y - getHalfHeight());
  }

  /**
   * Set the scale of the ZoomPanLayout while maintaining the current center point.
   *
   * @param scale The new value of the ZoomPanLayout scale.
   */
  public void setScaleFromCenter(float scale) {
    setScaleFromPosition(getHalfWidth(), getHalfHeight(), scale);
  }

  /**
   * Scrolls the ZoomPanLayout to the x and y values provided using scrolling animation.
   *
   * @param x Horizontal destination point.
   * @param y Vertical destination point.
   */
  public void slideTo(int x, int y) {
    getAnimator().animatePan(x, y);
  }

  /**
   * Scrolls and centers the ZoomPanLayout to the x and y values provided using scrolling animation.
   *
   * @param x Horizontal destination point.
   * @param y Vertical destination point.
   */
  public void slideToAndCenter(int x, int y) {
    slideTo(x - getHalfWidth(), y - getHalfHeight());
  }

  /**
   * Animates the ZoomPanLayout to the scale provided, and centers the viewport to the position
   * supplied.
   *
   * @param x     Horizontal destination point.
   * @param y     Vertical destination point.
   * @param scale The final scale value the ZoomPanLayout should animate to.
   */
  public void slideToAndCenterWithScale(int x, int y, float scale) {
    getAnimator().animateZoomPan(x - getHalfWidth(), y - getHalfHeight(), scale);
  }

  /**
   * Scales the ZoomPanLayout with animated progress, without maintaining scroll position.
   *
   * @param destination The final scale value the ZoomPanLayout should animate to.
   */
  public void smoothScaleTo(float destination) {
    getAnimator().animateZoom(destination);
  }

  /**
   * Animates the ZoomPanLayout to the scale provided, while maintaining position determined by
   * the focal point provided.
   *
   * @param focusX The horizontal focal point to maintain, relative to the screen (as supplied by MotionEvent.getX).
   * @param focusY The vertical focal point to maintain, relative to the screen (as supplied by MotionEvent.getY).
   * @param scale  The final scale value the ZoomPanLayout should animate to.
   */
  public void smoothScaleFromFocalPoint(int focusX, int focusY, float scale) {
    scale = getConstrainedDestinationScale(scale);
    if (scale == mScale) {
      return;
    }
    int x = getOffsetScrollXFromScale(focusX, scale, mScale);
    int y = getOffsetScrollYFromScale(focusY, scale, mScale);
    getAnimator().animateZoomPan(x, y, scale);
  }

  /**
   * Animate the scale of the ZoomPanLayout while maintaining the current center point.
   *
   * @param scale The final scale value the ZoomPanLayout should animate to.
   */
  public void smoothScaleFromCenter(float scale) {
    smoothScaleFromFocalPoint(getHalfWidth(), getHalfHeight(), scale);
  }

  /**
   * Provide this method to be overriden by subclasses, e.g., onScrollChanged.
   */
  public void onScaleChanged(float currentScale, float previousScale) {
    // noop
  }

  private float getConstrainedDestinationScale(float scale) {
    float currentMinumumScale = mShouldScaleToFit ? mEffectiveMinScale : mMinScale;
    scale = Math.max(scale, currentMinumumScale);
    scale = Math.min(scale, mMaxScale);
    return scale;
  }

  private void constrainScrollToLimits() {
    int x = getScrollX();
    int y = getScrollY();
    int constrainedX = getConstrainedScrollX(x);
    int constrainedY = getConstrainedScrollY(y);
    if (x != constrainedX || y != constrainedY) {
      scrollTo(constrainedX, constrainedY);
    }
  }

  private void updateScaledDimensions() {
    mScaledWidth = FloatMathHelper.scale(mBaseWidth, mScale);
    mScaledHeight = FloatMathHelper.scale(mBaseHeight, mScale);
  }

  protected ZoomPanAnimator getAnimator() {
    if (mZoomPanAnimator == null) {
      mZoomPanAnimator = new ZoomPanAnimator(this);
      mZoomPanAnimator.setDuration(mAnimationDuration);
    }
    return mZoomPanAnimator;
  }

  private int getOffsetScrollXFromScale(int offsetX, float destinationScale, float currentScale) {
    int scrollX = getScrollX() + offsetX;
    float deltaScale = destinationScale / currentScale;
    return (int) (scrollX * deltaScale) - offsetX;
  }

  private int getOffsetScrollYFromScale(int offsetY, float destinationScale, float currentScale) {
    int scrollY = getScrollY() + offsetY;
    float deltaScale = destinationScale / currentScale;
    return (int) (scrollY * deltaScale) - offsetY;
  }

  public void setScaleFromPosition(int offsetX, int offsetY, float scale) {
    scale = getConstrainedDestinationScale(scale);
    if (scale == mScale) {
      return;
    }
    int x = getOffsetScrollXFromScale(offsetX, scale, mScale);
    int y = getOffsetScrollYFromScale(offsetY, scale, mScale);

    setScale(scale);

    x = getConstrainedScrollX(x);
    y = getConstrainedScrollY(y);

    scrollTo(x, y);
  }

  @Override
  public boolean canScrollHorizontally(int direction) {
    int position = getScrollX();
    return direction > 0 ? position < getScrollLimitX() : direction < 0 && position > 0;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    boolean gestureIntercept = mGestureDetector.onTouchEvent(event);
    boolean scaleIntercept = mScaleGestureDetector.onTouchEvent(event);
    boolean touchIntercept = mTouchUpGestureDetector.onTouchEvent(event);
    if (edgeGlowEffect != null) {
      edgeGlowEffect.processTouchEvent(event);
    }
    return gestureIntercept || scaleIntercept || touchIntercept || super.onTouchEvent(event);
  }

  @Override
  public void draw(Canvas canvas) {
    super.draw(canvas);
    if (edgeGlowEffect != null) {
      edgeGlowEffect.draw(canvas);
    }
  }

  @Override
  public void scrollTo(int x, int y) {
    x = getConstrainedScrollX(x);
    y = getConstrainedScrollY(y);
    if (onScrollChangeListener != null) {
      onScrollChangeListener.onScrollChanged(x, y);
    }
    super.scrollTo(x, y);
  }

  private void calculateMinimumScaleToFit() {
    if (mShouldScaleToFit) {
      float minimumScaleX = getWidth() / (float) mBaseWidth;
      float minimumScaleY = getHeight() / (float) mBaseHeight;
      float recalculatedMinScale = minimumScaleX;
      if (recalculatedMinScale != mEffectiveMinScale) {
        mEffectiveMinScale = mMinScale = recalculatedMinScale;
        if (mScale < mEffectiveMinScale) {
          setScale(mEffectiveMinScale);
          resetScaleToFit = false;
        }
      }
    }
  }

  protected int getHalfWidth() {
    return FloatMathHelper.scale(getWidth(), 0.5f);
  }

  protected int getHalfHeight() {
    return FloatMathHelper.scale(getHeight(), 0.5f);
  }

  private int getConstrainedScrollX(int x) {
    return Math.max(0, Math.min(x, getScrollLimitX()));
  }

  private int getConstrainedScrollY(int y) {
    return Math.max(0, Math.min(y, getScrollLimitY()));
  }

  private int getScrollLimitX() {
    return mScaledWidth - getWidth();
  }

  private int getScrollLimitY() {
    return mScaledHeight - getHeight();
  }

  @Override
  public void computeScroll() {
    if (mScroller.computeScrollOffset()) {
      int startX = getScrollX();
      int startY = getScrollY();
      int endX = getConstrainedScrollX(mScroller.getCurrX());
      int endY = getConstrainedScrollY(mScroller.getCurrY());
      if (startX != endX || startY != endY) {
        scrollTo(endX, endY);
        if (mIsFlinging) {
          broadcastFlingUpdate();
        }
      }
      if (mScroller.isFinished()) {
        if (mIsFlinging) {
          mIsFlinging = false;
          broadcastFlingEnd();
        }
      } else {
        ViewCompat.postInvalidateOnAnimation(this);
      }
    }
  }

  private void broadcastDragBegin() {
    for (PanListener listener : mPanListeners) {
      listener.onPanBegin(getScrollX(), getScrollY(), Origination.DRAG);
    }
  }

  private void broadcastDragUpdate() {
    for (PanListener listener : mPanListeners) {
      listener.onPanUpdate(getScrollX(), getScrollY(), Origination.DRAG);
    }
  }

  private void broadcastDragEnd() {
    for (PanListener listener : mPanListeners) {
      listener.onPanEnd(getScrollX(), getScrollY(), Origination.DRAG);
    }
  }

  private void broadcastFlingBegin() {
    for (PanListener listener : mPanListeners) {
      listener.onPanBegin(mScroller.getStartX(), mScroller.getStartY(), Origination.FLING);
    }
  }

  private void broadcastFlingUpdate() {
    for (PanListener listener : mPanListeners) {
      listener.onPanUpdate(mScroller.getCurrX(), mScroller.getCurrY(), Origination.FLING);
    }
  }

  private void broadcastFlingEnd() {
    for (PanListener listener : mPanListeners) {
      listener.onPanEnd(mScroller.getFinalX(), mScroller.getFinalY(), Origination.FLING);
    }
  }

  private void broadcastProgrammaticPanBegin() {
    for (PanListener listener : mPanListeners) {
      listener.onPanBegin(getScrollX(), getScrollY(), null);
    }
  }

  private void broadcastProgrammaticPanUpdate() {
    for (PanListener listener : mPanListeners) {
      listener.onPanUpdate(getScrollX(), getScrollY(), null);
    }
  }

  private void broadcastProgrammaticPanEnd() {
    for (PanListener listener : mPanListeners) {
      listener.onPanEnd(getScrollX(), getScrollY(), null);
    }
  }

  private void broadcastPinchBegin() {
    for (ZoomListener listener : mZoomListeners) {
      listener.onZoomBegin(mScale, Origination.PINCH);
    }
  }

  private void broadcastPinchUpdate() {
    for (ZoomListener listener : mZoomListeners) {
      listener.onZoomUpdate(mScale, Origination.PINCH);
    }
  }

  private void broadcastPinchEnd() {
    for (ZoomListener listener : mZoomListeners) {
      listener.onZoomEnd(mScale, Origination.PINCH);
    }
  }

  private void broadcastProgrammaticZoomBegin() {
    for (ZoomListener listener : mZoomListeners) {
      listener.onZoomBegin(mScale, null);
    }
  }

  private void broadcastProgrammaticZoomUpdate() {
    for (ZoomListener listener : mZoomListeners) {
      listener.onZoomUpdate(mScale, null);
    }
  }

  private void broadcastProgrammaticZoomEnd() {
    for (ZoomListener listener : mZoomListeners) {
      listener.onZoomEnd(mScale, null);
    }
  }

  @Override
  public boolean onDown(MotionEvent event) {
    if (mIsFlinging && !mScroller.isFinished()) {
      mScroller.forceFinished(true);
      mIsFlinging = false;
      broadcastFlingEnd();
    }
    return true;
  }

  @Override
  public boolean onFling(MotionEvent event1, MotionEvent event2, float velocityX, float velocityY) {
    mScroller.fling( getScrollX(), getScrollY(), (int) -velocityX, (int) -velocityY, 0,
            getScrollLimitX(), 0, getScrollLimitY() );
    mIsFlinging = true;
    ViewCompat.postInvalidateOnAnimation(this);
    broadcastFlingBegin();
    return true;
  }

  @Override
  public void onLongPress(MotionEvent event) {

  }

  @Override
  public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
    int scrollEndX = getScrollX() + (int) distanceX;
    int scrollEndY = getScrollY() + (int) distanceY;
    scrollTo(scrollEndX, scrollEndY);
    if (!mIsDragging) {
      mIsDragging = true;
      broadcastDragBegin();
    } else {
      broadcastDragUpdate();
    }
    return true;
  }

  @Override
  public void onShowPress(MotionEvent event) {

  }

  @Override
  public boolean onSingleTapUp(MotionEvent event) {
    return true;
  }

  @Override
  public boolean onSingleTapConfirmed(MotionEvent event) {
    return true;
  }

  @Override
  public boolean onDoubleTap(MotionEvent event) {
    float effectiveDestination = mShouldLoopScale && mScale >= mMaxScale ? mMinScale : mMaxScale;
    float destination = getConstrainedDestinationScale(effectiveDestination);
    smoothScaleFromFocalPoint((int) event.getX(), (int) event.getY(), destination);
    return true;
  }

  @Override
  public boolean onDoubleTapEvent(MotionEvent event) {
    return true;
  }

  @Override
  public boolean onTouchUp(MotionEvent event) {
    if (mIsDragging) {
      mIsDragging = false;
      if (!mIsFlinging) {
        broadcastDragEnd();
      }
    }
    return true;
  }

  @Override
  public boolean onScaleBegin(ScaleGestureDetector scaleGestureDetector) {
    mIsScaling = true;
    broadcastPinchBegin();
    return true;
  }

  @Override
  public void onScaleEnd(ScaleGestureDetector scaleGestureDetector) {
    mIsScaling = false;
    broadcastPinchEnd();
  }

  @Override
  public boolean onScale(ScaleGestureDetector scaleGestureDetector) {
    float currentScale = mScale * mScaleGestureDetector.getScaleFactor();
    setScaleFromPosition(
            (int) scaleGestureDetector.getFocusX(),
            (int) scaleGestureDetector.getFocusY(),
            currentScale);
    broadcastPinchUpdate();
    return true;
  }

  private static class ZoomPanAnimator extends ValueAnimator implements
          ValueAnimator.AnimatorUpdateListener,
          ValueAnimator.AnimatorListener {

    private WeakReference<ZoomPanLayout> mZoomPanLayoutWeakReference;
    private ZoomPanState mStartState = new ZoomPanState();
    private ZoomPanState mEndState = new ZoomPanState();
    private boolean mHasPendingZoomUpdates;
    private boolean mHasPendingPanUpdates;

    public ZoomPanAnimator(ZoomPanLayout zoomPanLayout) {
      super();
      addUpdateListener(this);
      addListener(this);
      setFloatValues(0f, 1f);
      setInterpolator(new FastEaseInInterpolator());
      mZoomPanLayoutWeakReference = new WeakReference<ZoomPanLayout>(zoomPanLayout);
    }

    private boolean setupPanAnimation(int x, int y) {
      ZoomPanLayout zoomPanLayout = mZoomPanLayoutWeakReference.get();
      if (zoomPanLayout != null) {
        mStartState.x = zoomPanLayout.getScrollX();
        mStartState.y = zoomPanLayout.getScrollY();
        mEndState.x = x;
        mEndState.y = y;
        return mStartState.x != mEndState.x || mStartState.y != mEndState.y;
      }
      return false;
    }

    private boolean setupZoomAnimation(float scale) {
      ZoomPanLayout zoomPanLayout = mZoomPanLayoutWeakReference.get();
      if (zoomPanLayout != null) {
        mStartState.scale = zoomPanLayout.getScale();
        mEndState.scale = scale;
        return mStartState.scale != mEndState.scale;
      }
      return false;
    }

    public void animateZoomPan(int x, int y, float scale) {
      ZoomPanLayout zoomPanLayout = mZoomPanLayoutWeakReference.get();
      if (zoomPanLayout != null) {
        mHasPendingZoomUpdates = setupZoomAnimation(scale);
        mHasPendingPanUpdates = setupPanAnimation(x, y);
        if (mHasPendingPanUpdates || mHasPendingZoomUpdates) {
          start();
        }
      }
    }

    public void animateZoom(float scale) {
      ZoomPanLayout zoomPanLayout = mZoomPanLayoutWeakReference.get();
      if (zoomPanLayout != null) {
        mHasPendingZoomUpdates = setupZoomAnimation(scale);
        if (mHasPendingZoomUpdates) {
          start();
        }
      }
    }

    public void animatePan(int x, int y) {
      ZoomPanLayout zoomPanLayout = mZoomPanLayoutWeakReference.get();
      if (zoomPanLayout != null) {
        mHasPendingPanUpdates = setupPanAnimation(x, y);
        if (mHasPendingPanUpdates) {
          start();
        }
      }
    }

    @Override
    public void onAnimationUpdate(ValueAnimator animation) {
      ZoomPanLayout zoomPanLayout = mZoomPanLayoutWeakReference.get();
      if (zoomPanLayout != null) {
        float progress = (float) animation.getAnimatedValue();
        if (mHasPendingZoomUpdates) {
          float scale = mStartState.scale + (mEndState.scale - mStartState.scale) * progress;
          zoomPanLayout.setScale(scale);
          zoomPanLayout.broadcastProgrammaticZoomUpdate();
        }
        if (mHasPendingPanUpdates) {
          int x = (int) (mStartState.x + (mEndState.x - mStartState.x) * progress);
          int y = (int) (mStartState.y + (mEndState.y - mStartState.y) * progress);
          zoomPanLayout.scrollTo(x, y);
          zoomPanLayout.broadcastProgrammaticPanUpdate();
        }
      }
    }

    @Override
    public void onAnimationStart(Animator animator) {
      ZoomPanLayout zoomPanLayout = mZoomPanLayoutWeakReference.get();
      if (zoomPanLayout != null) {
        if (mHasPendingZoomUpdates) {
          zoomPanLayout.mIsScaling = true;
          zoomPanLayout.broadcastProgrammaticZoomBegin();
        }
        if (mHasPendingPanUpdates) {
          zoomPanLayout.mIsSliding = true;
          zoomPanLayout.broadcastProgrammaticPanBegin();
        }
      }
      zoomPanLayout.doubleTap = true;
    }

    @Override
    public void onAnimationEnd(Animator animator) {
      ZoomPanLayout zoomPanLayout = mZoomPanLayoutWeakReference.get();
      if (zoomPanLayout != null) {
        if (mHasPendingZoomUpdates) {
          mHasPendingZoomUpdates = false;
          zoomPanLayout.mIsScaling = false;
          zoomPanLayout.broadcastProgrammaticZoomEnd();
        }
        if (mHasPendingPanUpdates) {
          mHasPendingPanUpdates = false;
          zoomPanLayout.mIsSliding = false;
          zoomPanLayout.broadcastProgrammaticPanEnd();
        }
      }
      zoomPanLayout.doubleTap = false;
    }

    @Override
    public void onAnimationCancel(Animator animator) {
      onAnimationEnd(animator);
    }

    @Override
    public void onAnimationRepeat(Animator animator) {

    }

    private static class ZoomPanState {
      public int x;
      public int y;
      public float scale;
    }

    private static class FastEaseInInterpolator implements Interpolator {
      @Override
      public float getInterpolation(float input) {
        return (float) (1 - Math.pow(1 - input, 8));
      }
    }
  }

  public enum Origination {
    DRAG,
    FLING,
    PINCH
  }

  public interface ZoomListener {
    void onZoomBegin(float scale, Origination origin);

    void onZoomUpdate(float scale, Origination origin);

    void onZoomEnd(float scale, Origination origin);
  }

  public interface PanListener {
    void onPanBegin(int x, int y, Origination origin);

    void onPanUpdate(int x, int y, Origination origin);

    void onPanEnd(int x, int y, Origination origin);
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent ev) {
    switch (ev.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        mStartX = ev.getX();
        mStartY = ev.getY();
        break;
      case MotionEvent.ACTION_MOVE:
        float curX = ev.getX();
        float curY = ev.getY();
        if ((Math.abs(curX - mStartX) > mMoveThreshold) || (Math.abs(curY - mStartY)) > mMoveThreshold) {
          ev.setAction(MotionEvent.ACTION_DOWN);
          mGestureDetector.onTouchEvent(ev);
          return true;
        }
        break;
    }
    return false;
  }

  public void setMaxScaleFactor(float scaleFactor) {
    mScaleFactor = scaleFactor;
  }

  public void makeImmuneToScale(@IdRes int id) {
    if (!idsImmuneToScale.contains(id)) {
      idsImmuneToScale.add(id);
    }
  }

  public void setOnScrollChangeListener(OnScrollChangeListener listener) {
    onScrollChangeListener = listener;
  }

  public void executeOnZoomEnd(Runnable runnable) {
      mInternalZoomListener.zoomEndRunnables.add(runnable);
  }

  public void executeOnZoomBegin(Runnable runnable) {
      mInternalZoomListener.zoomBeginRunnables.add(runnable);
  }

  public void executeOnZoomUpdate(Runnable runnable) {
      mInternalZoomListener.zoomUpdateRunnables.add(runnable);
  }

  private class EdgeGlowEffect {

    private float mTouchSlop;
    private EdgeEffectCompat mEdgeGlowEffectRight, mEdgeGlowEffectLeft;
    private EdgeEffectCompat mEdgeGlowEffectTop, mEdgeGlowEffectBottom;
    private int mLastMotionX;
    private int mLastMotionY;
    private int mActivePointerId = INVALID_POINTER;
    private boolean mIsBeingDragged;

    EdgeGlowEffect(Context context) {
      mEdgeGlowEffectTop = new EdgeEffectCompat(context);
      mEdgeGlowEffectBottom = new EdgeEffectCompat(context);
      mEdgeGlowEffectLeft = new EdgeEffectCompat(context);
      mEdgeGlowEffectRight = new EdgeEffectCompat(context);
      mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    void processTouchEvent(MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          // Remember where the motion event started
          mLastMotionX = (int) event.getX();
          mLastMotionY = (int) event.getY();
          mActivePointerId = event.getPointerId(0);
          break;

        case MotionEvent.ACTION_MOVE:
          final int activePointerIndex = event.findPointerIndex(mActivePointerId);
          if (activePointerIndex == -1) {
            mActivePointerId = event.getPointerId(0);
            break;
          }

          final int x = (int) event.getX(activePointerIndex);
          final int y = (int) event.getY(activePointerIndex);

          int deltaX = mLastMotionX - x;
          int deltaY = mLastMotionY - y;
          if (!mIsBeingDragged && Math.abs(deltaX) > mTouchSlop) {
            final ViewParent parent = getParent();
            if (parent != null) {
              parent.requestDisallowInterceptTouchEvent(true);
            }
            mIsBeingDragged = true;
            if (deltaX > 0) {
              deltaX -= mTouchSlop;
            } else {
              deltaX += mTouchSlop;
            }
          }

          if (mIsBeingDragged) {
            // Scroll to follow the motion event
            mLastMotionX = x;

            final int oldX = getScrollX();
            final int oldY = getScrollY();
            final int range = getScrollRange();
            final int overscrollMode = getOverScrollMode();
            final boolean canOverscroll = overscrollMode == OVER_SCROLL_ALWAYS ||
                    (overscrollMode == OVER_SCROLL_IF_CONTENT_SCROLLS && range > 0);

            if (canOverscroll) {
              final int pulledToX = oldX + deltaX;
              final int pulledToY = oldY + deltaY;

              if (pulledToX < 0) {
                mEdgeGlowEffectLeft.onPull((float) deltaX / getWidth());
                if (!mEdgeGlowEffectRight.isFinished()) {
                  mEdgeGlowEffectRight.onRelease();
                }
              } else if (pulledToX > getScrollLimitX()) {
                mEdgeGlowEffectRight.onPull((float) deltaX / getWidth());
                if (!mEdgeGlowEffectLeft.isFinished()) {
                  mEdgeGlowEffectLeft.onRelease();
                }
              }

              if (pulledToY < 0) {
                mEdgeGlowEffectTop.onPull((float) deltaY / getHeight());
                if (!mEdgeGlowEffectBottom.isFinished()) {
                  mEdgeGlowEffectBottom.onRelease();
                }
              } else if (pulledToY > getScrollLimitY()) {
                mEdgeGlowEffectBottom.onPull((float) deltaY / getHeight());
                if (!mEdgeGlowEffectTop.isFinished()) {
                  mEdgeGlowEffectTop.onRelease();
                }
              }

              if (mEdgeGlowEffectLeft != null
                      && (!mEdgeGlowEffectLeft.isFinished() || !mEdgeGlowEffectRight.isFinished())) {
                postInvalidateOnAnimation();
              }

              if (mEdgeGlowEffectTop != null
                      && (!mEdgeGlowEffectTop.isFinished() || !mEdgeGlowEffectBottom.isFinished())) {
                postInvalidateOnAnimation();
              }
            }
          }
          break;

        case MotionEvent.ACTION_CANCEL:
        case MotionEvent.ACTION_UP:
          if (mEdgeGlowEffectLeft != null) {
            mEdgeGlowEffectLeft.onRelease();
            mEdgeGlowEffectRight.onRelease();
          }
          break;
      }
    }

    void draw(Canvas canvas) {
      if (mEdgeGlowEffectLeft != null) {
        if (mScale > mEffectiveMinScale) {
          drawHorizontalEdgeGlowEffect(canvas);
        }
        drawVerticalEdgeGlowEffect(canvas);
      }
    }

    private void drawVerticalEdgeGlowEffect(Canvas canvas) {
      final int scrollX = getScrollX();
      final int scrollY = getScrollY();

      if (!mEdgeGlowEffectTop.isFinished()) {
        final int restoreCount = canvas.save();
        final int width = getWidth() - getPaddingLeft() - getPaddingRight();

        canvas.translate(getPaddingLeft() + scrollX, Math.min(0, scrollY));
        mEdgeGlowEffectTop.setSize(width, getHeight());
        if (mEdgeGlowEffectTop.draw(canvas)) {
          postInvalidateOnAnimation();
        }
        canvas.restoreToCount(restoreCount);
      }
      if (!mEdgeGlowEffectBottom.isFinished()) {
        final int restoreCount = canvas.save();
        final int width = getWidth() - getPaddingLeft() - getPaddingRight();
        final int height = getHeight();

        canvas.translate(-width + getPaddingLeft() + scrollX,
                scrollY + height);
        canvas.rotate(180, width, 0);
        mEdgeGlowEffectBottom.setSize(width, height);
        if (mEdgeGlowEffectBottom.draw(canvas)) {
          postInvalidateOnAnimation();
        }
        canvas.restoreToCount(restoreCount);
      }
    }

    private void drawHorizontalEdgeGlowEffect(Canvas canvas) {
      final int scrollX = getScrollX();
      if (!mEdgeGlowEffectLeft.isFinished()) {
        final int restoreCount = canvas.save();
        final int height = getHeight() - getPaddingTop() - getPaddingBottom();

        canvas.rotate(270);
        canvas.translate(-(getScrollY() + height) + getPaddingTop(), Math.min(0, scrollX));
        mEdgeGlowEffectLeft.setSize(height, getWidth());
        if (mEdgeGlowEffectLeft.draw(canvas)) {
          postInvalidateOnAnimation();
        }
        canvas.restoreToCount(restoreCount);
      }
      if (!mEdgeGlowEffectRight.isFinished()) {
        final int restoreCount = canvas.save();
        final int width = getWidth();
        final int height = getHeight() - getPaddingTop() - getPaddingBottom();

        canvas.rotate(90);
        canvas.translate(-(getPaddingTop() - getScrollY()),
                -(scrollX + width));
        mEdgeGlowEffectRight.setSize(height, width);
        if (mEdgeGlowEffectRight.draw(canvas)) {
          postInvalidateOnAnimation();
        }
        canvas.restoreToCount(restoreCount);
      }
    }

    private int getScrollRange() {
      int scrollRange = 0;
      if (getChildCount() > 0) {
        View child = getChildAt(0);
        scrollRange = Math.max(0,
                (mScaledWidth - getPaddingLeft() - getPaddingRight()));
      }
      return scrollRange;
    }
  }

  private class InternalZoomListener implements ZoomListener {

    private List<Runnable> zoomBeginRunnables = new ArrayList<>();
    private List<Runnable> zoomUpdateRunnables = new ArrayList<>();
    private List<Runnable> zoomEndRunnables = new ArrayList<>();

    @Override
    public void onZoomBegin(float scale, Origination origin) {
      for (Runnable runnable : zoomBeginRunnables) {
        post(runnable);
      }
      zoomBeginRunnables.clear();
    }

    @Override
    public void onZoomUpdate(float scale, Origination origin) {
      for (Runnable runnable : zoomUpdateRunnables) {
        post(runnable);
      }
      zoomUpdateRunnables.clear();
    }

    @Override
    public void onZoomEnd(float scale, Origination origin) {
      for (Runnable runnable : zoomEndRunnables) {
        post(runnable);
      }
      zoomEndRunnables.clear();
    }
  }
}
