package com.example.attendacejil;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class BatchReviewPagerAdapter extends FragmentStateAdapter {

    private final String jsonFilePath;
    private final ReviewFragment[] fragments = new ReviewFragment[10];

    public BatchReviewPagerAdapter(@NonNull FragmentActivity fragmentActivity, String jsonFilePath) {
        super(fragmentActivity);
        this.jsonFilePath = jsonFilePath;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        // Position 0-9 maps to:
        // 0: W1S1, 1: W1S2, 2: W2S1, 3: W2S2, 4: W3S1, 5: W3S2, 6: W4S1, 7: W4S2, 8: W5S1, 9: W5S2
        int sunday = (position / 2) + 1;
        int service = (position % 2) + 1;
        
        // Count flagged rows so the UI shows the banner appropriately.
        // We consider a row flagged if it's flagged overall OR if its attendance for this column is true
        // but the network/name is invalid. Actually ReviewFragment checks it already, so just pass a dummy 0 
        // and let ReviewFragment compute the actual banner.
        int flaggedCount = 0; 
        
        ReviewFragment fragment = ReviewFragment.newInstance(jsonFilePath, sunday, service, flaggedCount);
        fragments[position] = fragment;
        return fragment;
    }

    @Override
    public int getItemCount() {
        return 10;
    }

    public ReviewFragment getFragment(int position) {
        return fragments[position];
    }
}
