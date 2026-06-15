package com.example.attendacejil;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class AlphabetIndexScroller extends View {

    private String[] alphabet = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"
    };

    private Paint textPaint;
    private int selectedIndex = -1;
    private RecyclerView recyclerView;
    private ReviewAdapter adapter;

    public AlphabetIndexScroller(Context context) {
        super(context);
        init();
    }

    public AlphabetIndexScroller(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        textPaint = new Paint();
        textPaint.setColor(Color.parseColor("#9E9E9E")); // text_secondary
        textPaint.setTextSize(30f);
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setRecyclerView(RecyclerView recyclerView, ReviewAdapter adapter) {
        this.recyclerView = recyclerView;
        this.adapter = adapter;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int height = getHeight();
        int width = getWidth();
        int singleHeight = height / alphabet.length;

        for (int i = 0; i < alphabet.length; i++) {
            if (i == selectedIndex) {
                textPaint.setColor(Color.parseColor("#42A5F5")); // accent_blue
                textPaint.setFakeBoldText(true);
            } else {
                textPaint.setColor(Color.parseColor("#9E9E9E"));
                textPaint.setFakeBoldText(false);
            }
            float xPos = width / 2f;
            float yPos = (singleHeight * i) + singleHeight;
            canvas.drawText(alphabet[i], xPos, yPos, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();
        float y = event.getY();
        int oldIndex = selectedIndex;
        int currentIdx = (int) (y / getHeight() * alphabet.length);

        switch (action) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                selectedIndex = -1;
                invalidate();
                break;
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // Fall through
            case MotionEvent.ACTION_MOVE:
                if (oldIndex != currentIdx) {
                    if (currentIdx >= 0 && currentIdx < alphabet.length) {
                        selectedIndex = currentIdx;
                        scrollToLetter(alphabet[currentIdx]);
                        invalidate();
                    }
                }
                break;
        }
        return true;
    }

    private void scrollToLetter(String letter) {
        if (adapter == null || recyclerView == null) return;
        java.util.List<AttendanceRow> rows = adapter.getRows();
        for (int i = 0; i < rows.size(); i++) {
            AttendanceRow r = rows.get(i);
            if (!r.markedForDeletion && r.lastName != null && r.lastName.toUpperCase().startsWith(letter)) {
                ((LinearLayoutManager) recyclerView.getLayoutManager()).scrollToPositionWithOffset(i, 0);
                return;
            }
        }
    }
}
