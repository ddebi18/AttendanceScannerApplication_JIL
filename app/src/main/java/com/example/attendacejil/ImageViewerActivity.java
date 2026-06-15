package com.example.attendacejil;

import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;

public class ImageViewerActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_URIS    = "image_uris";
    public static final String EXTRA_IMAGE_LABELS  = "image_labels";
    public static final String EXTRA_INITIAL_INDEX = "initial_index";

    private ViewPager2 viewPager;
    private TextView tvImageCounter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        viewPager      = findViewById(R.id.viewPagerImages);
        tvImageCounter = findViewById(R.id.tvImageCounter);
        ImageButton btnClose = findViewById(R.id.btnClose);

        btnClose.setOnClickListener(v -> finish());

        ArrayList<String> imageUris = getIntent().getStringArrayListExtra(EXTRA_IMAGE_URIS);
        int initialIndex = getIntent().getIntExtra(EXTRA_INITIAL_INDEX, 0);

        if (imageUris != null && !imageUris.isEmpty()) {
            ImageViewerAdapter adapter = new ImageViewerAdapter(imageUris);
            viewPager.setAdapter(adapter);

            // Navigate to the chosen image
            if (initialIndex > 0 && initialIndex < imageUris.size()) {
                viewPager.setCurrentItem(initialIndex, false);
            }

            updateCounter(viewPager.getCurrentItem() + 1, imageUris.size());

            viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    super.onPageSelected(position);
                    updateCounter(position + 1, imageUris.size());
                }
            });
        } else {
            tvImageCounter.setText("0 / 0");
        }
    }

    private void updateCounter(int current, int total) {
        tvImageCounter.setText(current + " / " + total);
    }
}
