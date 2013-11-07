/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.bitmap.drawable;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.Log;

import com.android.bitmap.BitmapCache;
import com.android.bitmap.DecodeTask;
import com.android.bitmap.DecodeTask.DecodeCallback;
import com.android.bitmap.DecodeTask.DecodeOptions;
import com.android.bitmap.NamedThreadFactory;
import com.android.bitmap.RequestKey;
import com.android.bitmap.RequestKey.Cancelable;
import com.android.bitmap.RequestKey.FileDescriptorFactory;
import com.android.bitmap.ReusableBitmap;
import com.android.bitmap.util.BitmapUtils;
import com.android.bitmap.util.RectUtils;
import com.android.bitmap.util.Trace;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * This class encapsulates the basic functionality needed to display a single image bitmap,
 * including request creation/cancelling, and data unbinding and re-binding.
 * <p>
 * The actual bitmap decode work is handled by {@link DecodeTask}.
 * <p>
 * If being used with a long-lived cache (static cache, attached to the Application instead of the
 * Activity, etc) then make sure to call {@link BasicBitmapDrawable#unbind()} at the appropriate
 * times so the cache has accurate unref counts. The
 * {@link com.android.bitmap.view.BitmapDrawableImageView} class has been created to do the
 * appropriate unbind operation when the view is detached from the window.
 */
public class BasicBitmapDrawable extends Drawable implements DecodeCallback,
        Drawable.Callback, RequestKey.Callback {

    protected static Rect sRect;

    protected RequestKey mCurrKey;
    protected RequestKey mPrevKey;
    protected int mDecodeWidth;
    protected int mDecodeHeight;

    protected final Paint mPaint = new Paint();
    private final BitmapCache mCache;

    private final boolean mLimitDensity;
    private final float mDensity;
    private ReusableBitmap mBitmap;
    private DecodeTask mTask;
    private Cancelable mCreateFileDescriptorFactoryTask;

    // based on framework CL:I015d77
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;

    private static final Executor SMALL_POOL_EXECUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, 1, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(128), new NamedThreadFactory("decode"));
    private static final Executor EXECUTOR = SMALL_POOL_EXECUTOR;

    private static final int MAX_BITMAP_DENSITY = DisplayMetrics.DENSITY_HIGH;
    private static final float VERTICAL_CENTER = 1f / 2;
    private static final float NO_MULTIPLIER = 1f;

    private static final String TAG = BasicBitmapDrawable.class.getSimpleName();
    private static final boolean DEBUG = DecodeTask.DEBUG;

    public BasicBitmapDrawable(final Resources res, final BitmapCache cache,
            final boolean limitDensity) {
        mDensity = res.getDisplayMetrics().density;
        mCache = cache;
        mLimitDensity = limitDensity;
        mPaint.setFilterBitmap(true);
        mPaint.setAntiAlias(true);
        mPaint.setDither(true);

        if (sRect == null) {
            sRect = new Rect();
        }
    }

    public final RequestKey getKey() {
        return mCurrKey;
    }

    protected ReusableBitmap getBitmap() {
        return mBitmap;
    }

    /**
     * Set the dimensions to decode into. These dimensions should never change while the drawable is
     * attached to the same cache, because caches can only contain bitmaps of one size for re-use.
     *
     * All UI operations should be called from the UI thread.
     */
    public final void setDecodeDimensions(int w, int h) {
        mDecodeWidth = w;
        mDecodeHeight = h;
        loadFileDescriptorFactory();
    }

    /**
     * Binds to the given key and start the decode process. This will first look in the cache, then
     * decode from the request key if not found.
     *
     * All UI operations should be called from the UI thread.
     */
    public void bind(RequestKey key) {
        Trace.beginSection("bind");
        setImage(key);
        Trace.endSection();
    }

    /**
     * Unbinds the current key and bitmap from the drawable. This will cause the bitmap to decrement
     * its ref count.
     *
     * All UI operations should be called from the UI thread.
     */
    public void unbind() {
        Trace.beginSection("unbind");
        setImage(null);
        Trace.endSection();
    }

    /**
     * Should only be overriden, not called.
     */
    protected void setImage(final RequestKey key) {
        if (mCurrKey != null && mCurrKey.equals(key)) {
            return;
        }

        Trace.beginSection("set image");
        Trace.beginSection("release reference");
        if (mBitmap != null) {
            mBitmap.releaseReference();
            mBitmap = null;
        }
        Trace.endSection();

        mPrevKey = mCurrKey;
        mCurrKey = key;

        if (mTask != null) {
            mTask.cancel();
            mTask = null;
        }
        if (mCreateFileDescriptorFactoryTask != null) {
            mCreateFileDescriptorFactoryTask.cancel();
            mCreateFileDescriptorFactoryTask = null;
        }

        if (key == null) {
            invalidateSelf();
            Trace.endSection();
            return;
        }

        // find cached entry here and skip decode if found.
        final ReusableBitmap cached = mCache.get(key, true /* incrementRefCount */);
        if (cached != null) {
            setBitmap(cached);
            if (DEBUG) {
                Log.d(TAG, String.format("CACHE HIT key=%s", mCurrKey));
            }
        } else {
            loadFileDescriptorFactory();
            if (DEBUG) {
                Log.d(TAG, String.format(
                        "CACHE MISS key=%s\ncache=%s", mCurrKey, mCache.toDebugString()));
            }
        }
        Trace.endSection();
    }

    /**
     * Should only be overriden, not called.
     */
    protected void setBitmap(ReusableBitmap bmp) {
        if (mBitmap != null && mBitmap != bmp) {
            mBitmap.releaseReference();
        }
        mBitmap = bmp;
        invalidateSelf();
    }

    /**
     * Should only be overriden, not called.
     */
    protected void loadFileDescriptorFactory() {
        if (mCurrKey == null || mDecodeWidth == 0 || mDecodeHeight == 0) {
            return;
        }

        // Create file descriptor if request supports it.
        mCreateFileDescriptorFactoryTask = mCurrKey
                .createFileDescriptorFactoryAsync(mCurrKey, this);
        if (mCreateFileDescriptorFactoryTask == null) {
            // Use input stream if request does not.
            decode(null);
        }
    }

    @Override
    public void fileDescriptorFactoryCreated(final RequestKey key,
            final FileDescriptorFactory factory) {
        if (mCreateFileDescriptorFactoryTask == null) {
            // Cancelled.
            return;
        }
        mCreateFileDescriptorFactoryTask = null;

        if (key.equals(mCurrKey)) {
            decode(factory);
        }
    }

    /**
     * Should only be overriden, not called.
     */
    protected void decode(final FileDescriptorFactory factory) {
        Trace.beginSection("decode");
        final int bufferW;
        final int bufferH;
        if (mLimitDensity) {
            final float scale =
                    Math.min(1f, (float) MAX_BITMAP_DENSITY / DisplayMetrics.DENSITY_DEFAULT
                            / mDensity);
            bufferW = (int) (mDecodeWidth * scale);
            bufferH = (int) (mDecodeHeight * scale);
        } else {
            bufferW = mDecodeWidth;
            bufferH = mDecodeHeight;
        }

        if (mTask != null) {
            mTask.cancel();
        }
        final DecodeOptions opts = new DecodeOptions(bufferW, bufferH, getDecodeVerticalCenter(),
                DecodeOptions.STRATEGY_ROUND_NEAREST);
        mTask = new DecodeTask(mCurrKey, opts, factory, this, mCache);
        mTask.executeOnExecutor(getExecutor());
        Trace.endSection();
    }

    protected Executor getExecutor() {
        return EXECUTOR;
    }

    protected float getDrawVerticalCenter() {
        return VERTICAL_CENTER;
    }

    protected float getDrawVerticalOffsetMultiplier() {
        return NO_MULTIPLIER;
    }

    protected float getDecodeVerticalCenter() {
        return VERTICAL_CENTER;
    }

    @Override
    public void draw(final Canvas canvas) {
        final Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }

        if (mBitmap != null && mBitmap.bmp != null) {
            BitmapUtils.calculateCroppedSrcRect(
                    mBitmap.getLogicalWidth(), mBitmap.getLogicalHeight(),
                    bounds.width(), bounds.height(),
                    bounds.height(), Integer.MAX_VALUE,
                    getDrawVerticalCenter(), false /* absoluteFraction */,
                    getDrawVerticalOffsetMultiplier(), sRect);

            final int orientation = mBitmap.getOrientation();
            // calculateCroppedSrcRect() gave us the source rectangle "as if" the orientation has
            // been corrected. We need to decode the uncorrected source rectangle. Calculate true
            // coordinates.
            RectUtils.rotateRectForOrientation(orientation,
                    new Rect(0, 0, mBitmap.getLogicalWidth(), mBitmap.getLogicalHeight()),
                    sRect);

            // We may need to rotate the canvas, so we also have to rotate the bounds.
            final Rect rotatedBounds = new Rect(bounds);
            RectUtils.rotateRect(orientation, bounds.centerX(), bounds.centerY(), rotatedBounds);

            // Rotate the canvas.
            canvas.save();
            canvas.rotate(orientation, bounds.centerX(), bounds.centerY());
            onDrawBitmap(canvas, sRect, rotatedBounds);
            canvas.restore();
        }
    }

    /**
     * Override this method to customize how to draw the bitmap to the canvas for the given bounds.
     * The bitmap to be drawn can be found at {@link #getBitmap()}.
     */
    protected void onDrawBitmap(final Canvas canvas, final Rect src, final Rect dst) {
        canvas.drawBitmap(mBitmap.bmp, src, dst, mPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        final int old = mPaint.getAlpha();
        mPaint.setAlpha(alpha);
        if (alpha != old) {
            invalidateSelf();
        }
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mPaint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return (mBitmap != null && (mBitmap.bmp.hasAlpha() || mPaint.getAlpha() < 255)) ?
                PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;
    }

    @Override
    public void onDecodeBegin(final RequestKey key) { }

    @Override
    public void onDecodeComplete(final RequestKey key, final ReusableBitmap result) {
        if (key.equals(mCurrKey)) {
            setBitmap(result);
        } else {
            // if the requests don't match (i.e. this request is stale), decrement the
            // ref count to allow the bitmap to be pooled
            if (result != null) {
                result.releaseReference();
            }
        }
    }

    @Override
    public void onDecodeCancel(final RequestKey key) { }

    @Override
    public void invalidateDrawable(Drawable who) {
        invalidateSelf();
    }

    @Override
    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        scheduleSelf(what, when);
    }

    @Override
    public void unscheduleDrawable(Drawable who, Runnable what) {
        unscheduleSelf(what);
    }
}
