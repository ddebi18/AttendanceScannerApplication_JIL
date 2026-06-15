package com.example.attendacejil;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class BatchReviewPagerAdapter extends FragmentStateAdapter {

    private final String jsonFilePath;
    private final ReviewFragment[] fragments = new ReviewFragment[10];
    private final java.util.List<Integer> activeColumns = new java.util.ArrayList<>();

    public BatchReviewPagerAdapter(@NonNull FragmentActivity fragmentActivity, String jsonFilePath, boolean[] disabledWeeks) {
        super(fragmentActivity);
        this.jsonFilePath = jsonFilePath;
        for (int i = 0; i < 10; i++) {
            int week = i / 2;
            if (disabledWeeks == null || week >= disabledWeeks.length || !disabledWeeks[week]) {
                activeColumns.add(i);
            }
        }
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        int colIdx = activeColumns.get(position);
        int sunday = (colIdx / 2) + 1;
        int service = (colIdx % 2) + 1;
        
        int flaggedCount = 0; 
        
        ReviewFragment fragment = ReviewFragment.newInstance(jsonFilePath, sunday, service, flaggedCount);
        fragments[colIdx] = fragment;
        return fragment;
    }

    @Override
    public int getItemCount() {
        return activeColumns.size();
    }

    public ReviewFragment getFragment(int colIdx) {
        return fragments[colIdx];
    }
    
    public int getColumnIndex(int position) {
        return activeColumns.get(position);
    }
}
