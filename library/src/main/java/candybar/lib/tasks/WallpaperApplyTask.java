package candybar.lib.tasks;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.afollestad.materialdialogs.MaterialDialog;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.danimahardhika.android.helpers.core.ColorHelper;
import com.danimahardhika.android.helpers.core.WindowHelper;
import com.danimahardhika.android.helpers.core.utils.LogUtil;
import com.danimahardhika.cafebar.CafeBar;
import com.danimahardhika.cafebar.CafeBarTheme;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.concurrent.Executor;

import candybar.lib.R;
import candybar.lib.helpers.TypefaceHelper;
import candybar.lib.helpers.WallpaperHelper;
import candybar.lib.items.ImageSize;
import candybar.lib.items.Wallpaper;
import candybar.lib.preferences.Preferences;

/*
 * CandyBar - Material Dashboard
 *
 * Copyright (c) 2014-2016 Dani Mahardhika
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

public class WallpaperApplyTask extends AsyncTask<Void, Void, Boolean> implements WallpaperPropertiesLoaderTask.Callback {

    private final WeakReference<Context> mContext;
    private Apply mApply;
    private RectF mRectF;
    private Executor mExecutor;
    private Wallpaper mWallpaper;
    private MaterialDialog mDialog;

    private WallpaperApplyTask(Context context) {
        mContext = new WeakReference<>(context);
        mApply = Apply.HOMESCREEN;
    }

    public WallpaperApplyTask to(Apply apply) {
        mApply = apply;
        return this;
    }

    public WallpaperApplyTask wallpaper(@NonNull Wallpaper wallpaper) {
        mWallpaper = wallpaper;
        return this;
    }

    public WallpaperApplyTask crop(@Nullable RectF rectF) {
        mRectF = rectF;
        return this;
    }

    public AsyncTask<Void, Void, Boolean> start() {
        return start(SERIAL_EXECUTOR);
    }

    public AsyncTask<Void, Void, Boolean> start(@NonNull Executor executor) {
        if (mDialog == null) {
            int color = mWallpaper.getColor();
            if (color == 0) {
                color = ColorHelper.getAttributeColor(mContext.get(), R.attr.colorAccent);
            }

            final MaterialDialog.Builder builder = new MaterialDialog.Builder(mContext.get());
            builder.widgetColor(color)
                    .typeface(TypefaceHelper.getMedium(mContext.get()), TypefaceHelper.getRegular(mContext.get()))
                    .progress(true, 0)
                    .cancelable(false)
                    .progressIndeterminateStyle(true)
                    .content(R.string.wallpaper_loading)
                    .positiveColor(color)
                    .positiveText(android.R.string.cancel)
                    .onPositive((dialog, which) -> cancel(true));

            mDialog = builder.build();
        }

        if (!mDialog.isShowing()) mDialog.show();

        mExecutor = executor;
        if (mWallpaper == null) {
            LogUtil.e("WallpaperApply cancelled, wallpaper is null");
            return null;
        }

        if (mWallpaper.getDimensions() == null) {
            return WallpaperPropertiesLoaderTask.prepare(mContext.get())
                    .wallpaper(mWallpaper)
                    .callback(this)
                    .start(AsyncTask.THREAD_POOL_EXECUTOR);
        }
        return executeOnExecutor(executor);
    }

    public static WallpaperApplyTask prepare(@NonNull Context context) {
        return new WallpaperApplyTask(context);
    }

    @Override
    public void onPropertiesReceived(Wallpaper wallpaper) {
        mWallpaper = wallpaper;
        if (mExecutor == null) mExecutor = SERIAL_EXECUTOR;
        if (mWallpaper.getDimensions() == null) {
            LogUtil.e("WallpaperApply cancelled, unable to retrieve wallpaper dimensions");

            if (mContext.get() == null) return;
            if (mContext.get() instanceof Activity) {
                if (((Activity) mContext.get()).isFinishing())
                    return;
            }

            if (mDialog != null && mDialog.isShowing()) {
                mDialog.dismiss();
            }

            Toast.makeText(mContext.get(), R.string.wallpaper_apply_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            executeOnExecutor(mExecutor);
        } catch (IllegalStateException e) {
            LogUtil.e(Log.getStackTraceString(e));
        }
    }

    @Override
    protected Boolean doInBackground(Void... voids) {
        if (!isCancelled()) {
            try {
                Thread.sleep(1);
                ImageSize imageSize = WallpaperHelper.getTargetSize(mContext.get());

                LogUtil.d("original rectF: " + mRectF);

                if (mRectF != null && Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT) {
                    Point point = WindowHelper.getScreenSize(mContext.get());
                    int height = point.y - WindowHelper.getStatusBarHeight(mContext.get()) - WindowHelper.getNavigationBarHeight(mContext.get());
                    float heightFactor = (float) imageSize.height / (float) height;
                    mRectF = WallpaperHelper.getScaledRectF(mRectF, heightFactor, 1f);
                }

                if (mRectF == null && Preferences.get(mContext.get()).isCropWallpaper()) {
                    /*
                     * Create a center crop rectF if wallpaper applied from grid, not opening the preview first
                     */
                    float widthScaleFactor = (float) imageSize.height / (float) mWallpaper.getDimensions().height;

                    float side = ((float) mWallpaper.getDimensions().width * widthScaleFactor - (float) imageSize.width) / 2f;
                    float leftRectF = 0f - side;
                    float rightRectF = (float) mWallpaper.getDimensions().width * widthScaleFactor - side;
                    float topRectF = 0f;
                    float bottomRectF = (float) imageSize.height;
                    mRectF = new RectF(leftRectF, topRectF, rightRectF, bottomRectF);
                    LogUtil.d("created center crop rectF: " + mRectF);
                }

                ImageSize adjustedSize = imageSize;
                RectF adjustedRectF = mRectF;

                float scaleFactor = (float) mWallpaper.getDimensions().height / (float) imageSize.height;
                if (scaleFactor > 1f) {
                    /*
                     * Applying original wallpaper size caused a problem (wallpaper zoomed in)
                     * if wallpaper dimension bigger than device screen resolution
                     *
                     * Solution: Resize wallpaper to match screen resolution
                     */

                    /*
                     * Use original wallpaper size:
                     * adjustedSize = new ImageSize(width, height);
                     */

                    /*
                     * Adjust wallpaper size to match screen resolution:
                     */
                    float widthScaleFactor = (float) imageSize.height / (float) mWallpaper.getDimensions().height;
                    int adjustedWidth = Float.valueOf((float) mWallpaper.getDimensions().width * widthScaleFactor).intValue();
                    adjustedSize = new ImageSize(adjustedWidth, imageSize.height);

                    if (adjustedRectF != null) {
                        /*
                         * If wallpaper crop enabled, original wallpaper size should be loaded first
                         */
                        adjustedSize = new ImageSize(mWallpaper.getDimensions().width, mWallpaper.getDimensions().height);
                        adjustedRectF = WallpaperHelper.getScaledRectF(mRectF, scaleFactor, scaleFactor);
                        LogUtil.d("adjusted rectF: " + adjustedRectF);
                    }

                    LogUtil.d(String.format(Locale.getDefault(), "adjusted bitmap: %d x %d",
                            adjustedSize.width, adjustedSize.height));
                }

                int call = 1;
                do {
                    /*
                     * Load the bitmap first
                     */
                    Bitmap loadedBitmap = Glide.with(mContext.get())
                            .asBitmap()
                            .load(mWallpaper.getURL())
                            .skipMemoryCache(true)
                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                            .submit()
                            .get();

                    if (loadedBitmap != null) {
                        try {
                            /*
                             * Checking if loaded bitmap resolution supported by the device
                             * If texture size too big then resize it
                             */
                            Bitmap bitmapTemp = Bitmap.createBitmap(
                                    loadedBitmap.getWidth(),
                                    loadedBitmap.getHeight(),
                                    loadedBitmap.getConfig());
                            bitmapTemp.recycle();

                            /*
                             * Texture size is ok
                             */
                            LogUtil.d(String.format(Locale.getDefault(), "loaded bitmap: %d x %d",
                                    loadedBitmap.getWidth(), loadedBitmap.getHeight()));
                            publishProgress();

                            Bitmap bitmap = loadedBitmap;
                            if (Preferences.get(mContext.get()).isCropWallpaper() && adjustedRectF != null) {
                                LogUtil.d("rectF: " + adjustedRectF);
                                /*
                                 * Cropping bitmap
                                 */
                                ImageSize targetSize = WallpaperHelper.getTargetSize(mContext.get());

                                int targetWidth = Double.valueOf(
                                        ((double) loadedBitmap.getHeight() / (double) targetSize.height)
                                                * (double) targetSize.width).intValue();

                                bitmap = Bitmap.createBitmap(
                                        targetWidth,
                                        loadedBitmap.getHeight(),
                                        loadedBitmap.getConfig());
                                Paint paint = new Paint();
                                paint.setFilterBitmap(true);
                                paint.setAntiAlias(true);
                                paint.setDither(true);

                                Canvas canvas = new Canvas(bitmap);
                                canvas.drawBitmap(loadedBitmap, null, adjustedRectF, paint);

                                float scale = (float) targetSize.height / (float) bitmap.getHeight();
                                if (scale < 1f) {
                                    LogUtil.d("bitmap size is bigger than screen resolution, resizing bitmap");
                                    int resizedWidth = Float.valueOf((float) bitmap.getWidth() * scale).intValue();
                                    bitmap = Bitmap.createScaledBitmap(bitmap, resizedWidth, targetSize.height, true);
                                }
                            }

                            /*
                             * Final bitmap generated
                             */
                            LogUtil.d(String.format(Locale.getDefault(), "generated bitmap: %d x %d ",
                                    bitmap.getWidth(), bitmap.getHeight()));

                            if (mApply == Apply.HOMESCREEN_LOCKSCREEN) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    WallpaperManager.getInstance(mContext.get().getApplicationContext()).setBitmap(
                                            bitmap, null, true, WallpaperManager.FLAG_LOCK | WallpaperManager.FLAG_SYSTEM);
                                    return true;
                                }
                            }

                            if (mApply == Apply.HOMESCREEN) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    WallpaperManager.getInstance(mContext.get().getApplicationContext()).setBitmap(
                                            bitmap, null, true, WallpaperManager.FLAG_SYSTEM);
                                    return true;
                                }

                                WallpaperManager.getInstance(mContext.get().getApplicationContext()).setBitmap(bitmap);
                                return true;
                            }

                            if (mApply == Apply.LOCKSCREEN) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    WallpaperManager.getInstance(mContext.get().getApplicationContext()).setBitmap(
                                            bitmap, null, true, WallpaperManager.FLAG_LOCK);
                                    return true;
                                }
                            }
                        } catch (OutOfMemoryError e) {
                            LogUtil.e("loaded bitmap is too big, resizing it ...");
                            /*
                             * Texture size is too big
                             * Resizing bitmap
                             */

                            double scale = 1 - (0.1 * call);
                            int scaledWidth = Double.valueOf(adjustedSize.width * scale).intValue();
                            int scaledHeight = Double.valueOf(adjustedSize.height * scale).intValue();

                            adjustedRectF = WallpaperHelper.getScaledRectF(adjustedRectF,
                                    (float) scale, (float) scale);
                            adjustedSize = new ImageSize(scaledWidth, scaledHeight);
                        }
                    }

                    /*
                     * Continue to next iteration
                     */
                    call++;
                } while (call <= 5 && !isCancelled());
                return false;
            } catch (Exception e) {
                LogUtil.e(Log.getStackTraceString(e));
                return false;
            }
        }
        return false;
    }

    @Override
    protected void onProgressUpdate(Void... values) {
        super.onProgressUpdate(values);
        mDialog.setContent(R.string.wallpaper_applying);
    }

    @Override
    protected void onCancelled(Boolean aBoolean) {
        super.onCancelled(aBoolean);
        Toast.makeText(mContext.get(), R.string.wallpaper_apply_cancelled,
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onPostExecute(Boolean aBoolean) {
        if (mContext.get() == null) {
            return;
        }

        if (((AppCompatActivity) mContext.get()).isFinishing()) {
            return;
        }

        if (mDialog != null && mDialog.isShowing()) {
            mDialog.dismiss();
        }

        if (aBoolean) {
            CafeBar.builder(mContext.get())
                    .theme(CafeBarTheme.Custom(ColorHelper.getAttributeColor(
                            mContext.get(), R.attr.card_background)))
                    .contentTypeface(TypefaceHelper.getRegular(mContext.get()))
                    .floating(true)
                    .fitSystemWindow()
                    .content(R.string.wallpaper_applied)
                    .show();
        } else {
            Toast.makeText(mContext.get(), R.string.wallpaper_apply_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    public enum Apply {
        LOCKSCREEN,
        HOMESCREEN,
        HOMESCREEN_LOCKSCREEN
    }
}
