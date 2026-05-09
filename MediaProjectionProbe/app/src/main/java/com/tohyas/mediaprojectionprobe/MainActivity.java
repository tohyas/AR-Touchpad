package com.tohyas.mediaprojectionprobe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private static final String TAG = "ProjectionProbe";
    private static final int REQUEST_MEDIA_PROJECTION = 42;

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread imageThread;
    private Handler imageHandler;

    private TextView infoText;
    private ImageView preview;
    private Bitmap latestBitmap;
    private final AtomicInteger frameCount = new AtomicInteger(0);

    private int requestedWidth;
    private int requestedHeight;
    private int requestedDpi;
    private int capturedWidth = -1;
    private int capturedHeight = -1;
    private long firstFrameTime = 0L;
    private long lastFrameTime = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        buildUi();
        updateInfo("Ready. Connect an external display, put a distinct app/window on it, then tap Start Capture.");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(24, 24, 24, 24);

        Button startButton = new Button(this);
        startButton.setText("Start MediaProjection Picker");
        startButton.setOnClickListener(v -> startCaptureFlow());
        root.addView(startButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        Button stopButton = new Button(this);
        stopButton.setText("Stop Capture");
        stopButton.setOnClickListener(v -> stopProjection());
        root.addView(stopButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        Button saveButton = new Button(this);
        saveButton.setText("Save Latest PNG");
        saveButton.setOnClickListener(v -> saveLatestBitmap());
        root.addView(saveButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        infoText = new TextView(this);
        infoText.setTextSize(14f);
        infoText.setPadding(0, 20, 0, 20);
        root.addView(infoText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setBackgroundColor(0xFFEEEEEE);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                900
        ));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void startCaptureFlow() {
        stopProjection();
        frameCount.set(0);
        capturedWidth = -1;
        capturedHeight = -1;
        firstFrameTime = 0L;
        lastFrameTime = 0L;
        Intent intent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_MEDIA_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            updateInfo("MediaProjection was cancelled.");
            return;
        }
        startProjection(resultCode, data);
    }

    private void startProjection(int resultCode, Intent data) {
        mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            updateInfo("getMediaProjection returned null.");
            return;
        }

        imageThread = new HandlerThread("projection-image-reader");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.d(TAG, "MediaProjection onStop");
                runOnUiThread(() -> updateInfo("MediaProjection stopped."));
                releaseProjectionObjects(false);
            }

            @Override
            public void onCapturedContentResize(int width, int height) {
                capturedWidth = width;
                capturedHeight = height;
                Log.d(TAG, "onCapturedContentResize " + width + "x" + height);
                runOnUiThread(() -> updateInfo(null));
            }

            @Override
            public void onCapturedContentVisibilityChanged(boolean isVisible) {
                Log.d(TAG, "onCapturedContentVisibilityChanged visible=" + isVisible);
                runOnUiThread(() -> updateInfo("Captured content visible=" + isVisible));
            }
        }, new Handler(getMainLooper()));

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        requestedWidth = metrics.widthPixels;
        requestedHeight = metrics.heightPixels;
        requestedDpi = metrics.densityDpi;

        imageReader = ImageReader.newInstance(
                requestedWidth,
                requestedHeight,
                PixelFormat.RGBA_8888,
                2
        );
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            try {
                Bitmap bitmap = imageToBitmap(image);
                int count = frameCount.incrementAndGet();
                long now = System.currentTimeMillis();
                if (firstFrameTime == 0L) firstFrameTime = now;
                lastFrameTime = now;
                latestBitmap = bitmap;
                if (count == 1 || count % 10 == 0) {
                    Log.d(TAG, "frame=" + count + " image=" + image.getWidth() + "x" + image.getHeight());
                }
                runOnUiThread(() -> {
                    preview.setImageBitmap(bitmap);
                    updateInfo(null);
                });
            } catch (Throwable t) {
                Log.e(TAG, "Failed to read projection image", t);
                runOnUiThread(() -> updateInfo("Failed to read projection image: " + t));
            } finally {
                image.close();
            }
        }, imageHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "MediaProjectionProbe",
                requestedWidth,
                requestedHeight,
                requestedDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                imageHandler
        );

        updateInfo("Projection started. Check whether the preview shows the phone screen, external display, both, or a selected app.");
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int paddedWidth = image.getWidth() + rowPadding / pixelStride;

        Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        if (padded != cropped) padded.recycle();
        return cropped;
    }

    private void saveLatestBitmap() {
        Bitmap bitmap = latestBitmap;
        if (bitmap == null) {
            updateInfo("No frame to save yet.");
            return;
        }
        try {
            File dir = new File(getExternalFilesDir(null), "projection-probe");
            if (!dir.exists() && !dir.mkdirs()) {
                updateInfo("Failed to create output directory: " + dir);
                return;
            }
            String name = "projection-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".png";
            File file = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            updateInfo("Saved: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save PNG", e);
            updateInfo("Failed to save PNG: " + e);
        }
    }

    private void stopProjection() {
        if (mediaProjection != null) {
            MediaProjection projection = mediaProjection;
            mediaProjection = null;
            projection.stop();
        }
        releaseProjectionObjects(true);
        updateInfo("Stopped.");
    }

    private void releaseProjectionObjects(boolean clearPreview) {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (imageThread != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                imageThread.quitSafely();
            } else {
                imageThread.quit();
            }
            imageThread = null;
            imageHandler = null;
        }
        if (clearPreview) {
            latestBitmap = null;
            if (preview != null) preview.setImageDrawable(null);
        }
    }

    private void updateInfo(String message) {
        if (infoText == null) return;
        StringBuilder sb = new StringBuilder();
        if (message != null && !message.isEmpty()) {
            sb.append(message).append("\n\n");
        }
        sb.append("Requested surface: ").append(requestedWidth).append(" x ").append(requestedHeight)
                .append(" @ ").append(requestedDpi).append(" dpi\n");
        sb.append("onCapturedContentResize: ")
                .append(capturedWidth > 0 ? capturedWidth : "-")
                .append(" x ")
                .append(capturedHeight > 0 ? capturedHeight : "-")
                .append("\n");
        sb.append("Frames: ").append(frameCount.get()).append("\n");
        if (latestBitmap != null) {
            sb.append("Latest bitmap: ").append(latestBitmap.getWidth()).append(" x ").append(latestBitmap.getHeight()).append("\n");
        }
        if (firstFrameTime > 0L && lastFrameTime > 0L) {
            sb.append("First frame: ").append(formatTime(firstFrameTime)).append("\n");
            sb.append("Latest frame: ").append(formatTime(lastFrameTime)).append("\n");
        }
        sb.append("\nChecklist:\n");
        sb.append("- If the preview shows only the phone screen, MediaProjection is not capturing the external display.\n");
        sb.append("- If it shows the external display, this is promising for AR Touchpad preview.\n");
        sb.append("- If the Android picker lets you choose an external-display app, test that option too.\n");
        sb.append("- If the callback size matches external display resolution, record it.\n");
        infoText.setText(sb.toString());
    }

    private String formatTime(long millis) {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(millis));
    }

    @Override
    protected void onDestroy() {
        stopProjection();
        super.onDestroy();
    }
}
