package com.example.attendacejil;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CameraActivity v3 — Simple Capture + Preview (no local enhancement)
 *
 * Users are expected to photograph a CamScanner-processed document.
 * The raw JPEG is captured and sent directly to the server, which
 * runs its own full preprocessing + OCR pipeline.
 *
 * STATE A (viewfinderLayout):
 *   • Live CameraX preview
 *   • EdgeOverlayView draws estimated document corners
 *   • SCAN button enabled when document detected with ≥70% confidence
 *
 * STATE B (previewLayout):
 *   • Raw still image — shown immediately, no background processing
 *   • "Retake" → back to STATE A
 *   • "Use Photo" → return raw path to caller
 *
 * Extras in:  EXTRA_IMAGE_COUNT (int)
 * Extras out: EXTRA_IMAGE_PATH  (String)
 */
public class CameraActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_PATH  = "image_path";
    public static final String EXTRA_IMAGE_COUNT = "image_count";

    private static final int   REQUEST_CAMERA    = 101;
    private static final long  EDGE_POLL_MS      = 800;
    private static final float CONFIDENCE_THRESH = 0.70f;

    // ── Viewfinder views ──────────────────────────────────────────────────────
    private FrameLayout     viewfinderLayout;
    private PreviewView     previewView;
    private EdgeOverlayView edgeOverlay;
    private TextView        tvCameraCount, tvScanHint, tvAdjustHint;
    private Button          btnScan;

    // ── Preview views ─────────────────────────────────────────────────────────
    private FrameLayout     previewLayout;
    private ImageView       imgPreview;
    private Button          btnRetake, btnAccept;

    // ── CameraX ───────────────────────────────────────────────────────────────
    private ImageCapture    imageCapture;
    private ExecutorService cameraExecutor;

    // ── State ─────────────────────────────────────────────────────────────────
    private File          pendingFile;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // ── Edge detection polling ────────────────────────────────────────────────
    private final Runnable edgeCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (previewView.getVisibility() != View.VISIBLE) return;
            detectEdgesFromPreview();
            uiHandler.postDelayed(this, EDGE_POLL_MS);
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        // Viewfinder state
        viewfinderLayout = findViewById(R.id.viewfinderLayout);
        previewView      = findViewById(R.id.previewView);
        edgeOverlay      = findViewById(R.id.edgeOverlay);
        tvCameraCount    = findViewById(R.id.tvCameraCount);
        tvScanHint       = findViewById(R.id.tvScanHint);
        tvAdjustHint     = findViewById(R.id.tvAdjustHint);
        btnScan          = findViewById(R.id.btnScan);

        // Preview state
        previewLayout = findViewById(R.id.previewLayout);
        imgPreview    = findViewById(R.id.imgPreview);
        btnRetake     = findViewById(R.id.btnRetake);
        btnAccept     = findViewById(R.id.btnAccept);

        cameraExecutor = Executors.newSingleThreadExecutor();

        int count = getIntent().getIntExtra(EXTRA_IMAGE_COUNT, 0);
        tvCameraCount.setText(count + " / 25");

        ((ImageButton) findViewById(R.id.btnClose)).setOnClickListener(v -> finish());
        btnScan  .setOnClickListener(v -> capturePhoto());
        btnRetake.setOnClickListener(v -> showViewfinder());
        btnAccept.setOnClickListener(v -> acceptPhoto());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (viewfinderLayout.getVisibility() == View.VISIBLE)
            uiHandler.postDelayed(edgeCheckRunnable, EDGE_POLL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(edgeCheckRunnable);
    }

    // ── Edge detection (heuristic) ────────────────────────────────────────────

    private void detectEdgesFromPreview() {
        try {
            android.graphics.Bitmap bmp = previewView.getBitmap();
            if (bmp == null) { updateEdgeState(0f, null); return; }

            int w = bmp.getWidth(), h = bmp.getHeight();
            long[] sample = sampleBrightness(bmp, 5);
            float variance = computeVariance(sample);

            float conf = Math.min(1f, variance / 3000f);

            PointF[] corners = null;
            if (conf >= CONFIDENCE_THRESH) {
                float mx = w * 0.05f, my = h * 0.04f;
                corners = new PointF[]{
                    new PointF(mx,     my),
                    new PointF(w - mx, my),
                    new PointF(w - mx, h - my),
                    new PointF(mx,     h - my)
                };
            }
            updateEdgeState(conf, corners);
        } catch (Exception ignored) {
            updateEdgeState(0f, null);
        }
    }

    private long[] sampleBrightness(android.graphics.Bitmap bmp, int gridN) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        long[] vals = new long[gridN * gridN];
        int i = 0;
        for (int gy = 0; gy < gridN; gy++)
            for (int gx = 0; gx < gridN; gx++) {
                int px = bmp.getPixel((int)(w * (gx + 0.5f) / gridN),
                                       (int)(h * (gy + 0.5f) / gridN));
                vals[i++] = ((px >> 16 & 0xFF) + (px >> 8 & 0xFF) + (px & 0xFF)) / 3L;
            }
        return vals;
    }

    private float computeVariance(long[] vals) {
        float mean = 0;
        for (long v : vals) mean += v;
        mean /= vals.length;
        float var = 0;
        for (long v : vals) var += (v - mean) * (v - mean);
        return var / vals.length;
    }

    private void updateEdgeState(float conf, PointF[] corners) {
        runOnUiThread(() -> {
            boolean found = conf >= CONFIDENCE_THRESH;
            edgeOverlay.setCorners(corners, found);
            btnScan.setEnabled(found);
            btnScan.setAlpha(found ? 1.0f : 0.4f);

            if (found) {
                tvScanHint.setText("✓ Document detected — tap SCAN");
                tvScanHint.setTextColor(0xFF81C784);
                tvAdjustHint.setVisibility(View.GONE);
            } else if (conf > 0.3f) {
                tvScanHint.setText("Adjusting — hold steady...");
                tvScanHint.setTextColor(0xFFFFB74D);
                tvAdjustHint.setVisibility(View.VISIBLE);
            } else {
                tvScanHint.setText("Align the attendance sheet within the frame");
                tvScanHint.setTextColor(0xCCFFFFFF);
                tvAdjustHint.setVisibility(View.GONE);
            }
        });
    }

    // ── STATE helpers ─────────────────────────────────────────────────────────

    private void showViewfinder() {
        if (pendingFile != null && pendingFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            pendingFile.delete();
            pendingFile = null;
        }
        previewLayout   .setVisibility(View.GONE);
        viewfinderLayout.setVisibility(View.VISIBLE);
        edgeOverlay.clear();
        uiHandler.postDelayed(edgeCheckRunnable, EDGE_POLL_MS);
    }

    /**
     * STATE B: Show the captured image immediately — no enhancement, no delay.
     * The raw JPEG is sent to the server exactly as captured. The server applies
     * its own preprocessing pipeline optimised for CamScanner-processed images.
     */
    private void showPreview(File rawFile) {
        uiHandler.removeCallbacks(edgeCheckRunnable);
        pendingFile = rawFile;

        viewfinderLayout.setVisibility(View.GONE);
        previewLayout   .setVisibility(View.VISIBLE);

        // Show image immediately — buttons enabled right away
        imgPreview.setImageBitmap(BitmapFactory.decodeFile(rawFile.getAbsolutePath()));
        btnAccept.setEnabled(true);
        btnRetake.setEnabled(true);
    }

    private void acceptPhoto() {
        if (pendingFile == null) return;
        Intent result = new Intent();
        result.putExtra(EXTRA_IMAGE_PATH, pendingFile.getAbsolutePath());
        setResult(RESULT_OK, result);
        finish();
    }

    // ── CameraX setup ─────────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();

                provider.unbindAll();
                provider.bindToLifecycle(this,
                        CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);

                uiHandler.postDelayed(edgeCheckRunnable, EDGE_POLL_MS);

            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Camera init failed: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ── Photo capture ─────────────────────────────────────────────────────────

    private void capturePhoto() {
        if (imageCapture == null) return;
        btnScan.setEnabled(false);

        String fileName = "attendance_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
                .format(new Date()) + ".jpg";
        File outputFile = new File(getCacheDir(), fileName);

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(outputFile).build();

        imageCapture.takePicture(options, cameraExecutor,
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) {
                        runOnUiThread(() -> showPreview(outputFile));
                    }
                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        runOnUiThread(() -> {
                            Toast.makeText(CameraActivity.this,
                                    "Capture failed: " + e.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                            btnScan.setEnabled(true);
                            btnScan.setAlpha(1.0f);
                        });
                    }
                });
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQUEST_CAMERA && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Camera permission required.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        cameraExecutor.shutdown();
    }
}
