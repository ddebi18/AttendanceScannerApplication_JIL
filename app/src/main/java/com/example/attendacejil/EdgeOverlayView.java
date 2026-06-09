package com.example.attendacejil;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.View;

/**
 * EdgeOverlayView
 *
 * A transparent overlay drawn on top of the CameraX PreviewView.
 * Draws the four-corner document boundary polygon detected by the
 * ML Kit / OpenCV document scanner.
 *
 * State:
 *   NONE    → no polygon drawn
 *   FOUND   → green polygon + corner dots
 *   MISSING → red border flash (no document detected)
 */
public class EdgeOverlayView extends View {

    public enum State { NONE, FOUND, MISSING }

    // ── Paint objects ─────────────────────────────────────────────────────────
    private final Paint paintLine   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintCorner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintFill   = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── State ─────────────────────────────────────────────────────────────────
    private State        state   = State.NONE;
    private PointF[]     corners = null;   // 4 points: TL, TR, BR, BL

    public EdgeOverlayView(Context context) {
        super(context);
        init();
    }

    public EdgeOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EdgeOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Semi-transparent fill
        paintFill.setStyle(Paint.Style.FILL);
        paintFill.setColor(Color.argb(40, 76, 175, 80));  // green tint

        // Border line
        paintLine.setStyle(Paint.Style.STROKE);
        paintLine.setStrokeWidth(4f);
        paintLine.setColor(Color.argb(220, 76, 175, 80)); // green

        // Corner dot
        paintCorner.setStyle(Paint.Style.FILL);
        paintCorner.setColor(Color.WHITE);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Update the overlay with newly detected corners.
     * @param pts  4 PointF corners in preview-space coordinates (TL, TR, BR, BL),
     *             already scaled to this view's dimensions. Pass null to hide.
     * @param found true = green overlay, false = red "not found" flash
     */
    public void setCorners(PointF[] pts, boolean found) {
        this.corners = pts;
        this.state   = (pts != null) ? State.FOUND
                      : (found ? State.NONE : State.MISSING);

        if (state == State.MISSING) {
            paintLine.setColor(Color.argb(200, 229, 57, 53));  // red
            paintFill.setColor(Color.argb(30, 229, 57, 53));
        } else {
            paintLine.setColor(Color.argb(220, 76, 175, 80));  // green
            paintFill.setColor(Color.argb(40, 76, 175, 80));
        }
        invalidate();
    }

    public void clear() {
        corners = null;
        state   = State.NONE;
        invalidate();
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (state == State.NONE || corners == null || corners.length < 4) {
            // Draw dim guide rectangle
            drawGuideRect(canvas);
            return;
        }

        // Build polygon path
        Path path = new Path();
        path.moveTo(corners[0].x, corners[0].y);
        path.lineTo(corners[1].x, corners[1].y);
        path.lineTo(corners[2].x, corners[2].y);
        path.lineTo(corners[3].x, corners[3].y);
        path.close();

        canvas.drawPath(path, paintFill);
        canvas.drawPath(path, paintLine);

        // Corner dots
        for (PointF pt : corners) {
            canvas.drawCircle(pt.x, pt.y, 14f, paintCorner);
        }
    }

    /** Draw a dim dashed guide rectangle when no document is detected. */
    private void drawGuideRect(Canvas canvas) {
        int w  = getWidth();
        int h  = getHeight();
        float margin = w * 0.06f;

        Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
        guide.setStyle(Paint.Style.STROKE);
        guide.setColor(Color.argb(80, 255, 255, 255));
        guide.setStrokeWidth(2f);

        canvas.drawRect(margin, margin * 2, w - margin, h - margin * 2, guide);
    }
}
