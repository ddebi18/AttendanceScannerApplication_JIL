package com.example.attendacejil;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.List;

public class ImageViewerAdapter extends RecyclerView.Adapter<ImageViewerAdapter.ImageViewHolder> {

    private final List<String> imageUris;

    public ImageViewerAdapter(List<String> imageUris) {
        this.imageUris = imageUris;
    }

    @NonNull
    @Override
    public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_viewer_image, parent, false);
        return new ImageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
        String uriStr = imageUris.get(position);
        Glide.with(holder.imageView.getContext())
                .load(uriStr)
                .into(holder.imageView);
    }

    @Override
    public int getItemCount() {
        return imageUris != null ? imageUris.size() : 0;
    }

    static class ImageViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;

        public ImageViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.ivFullscreenImage);
        }
    }
}
