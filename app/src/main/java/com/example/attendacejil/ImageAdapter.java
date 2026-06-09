package com.example.attendacejil;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.io.File;
import java.util.List;

/**
 * RecyclerView adapter for displaying captured attendance sheet thumbnails.
 *
 * Each item shows:
 *  - A 96×96 dp thumbnail of the captured image (loaded with Glide)
 *  - A badge in the top-left corner showing the 1-based index  (e.g. "#1")
 *  - A red "×" remove button in the top-right corner
 */
public class ImageAdapter extends RecyclerView.Adapter<ImageAdapter.ImageViewHolder> {

    public interface OnRemoveClickListener {
        void onRemove(int position);
    }

    private final Context context;
    private final List<String> imagePaths;   // absolute file paths
    private final OnRemoveClickListener removeListener;

    public ImageAdapter(Context context,
                        List<String> imagePaths,
                        OnRemoveClickListener removeListener) {
        this.context = context;
        this.imagePaths = imagePaths;
        this.removeListener = removeListener;
    }

    @NonNull
    @Override
    public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context)
                .inflate(R.layout.item_image, parent, false);
        return new ImageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
        String path = imagePaths.get(position);

        // Load thumbnail with Glide
        Glide.with(context)
                .load(new File(path))
                .centerCrop()
                .placeholder(R.drawable.ic_image_placeholder)
                .into(holder.imgThumbnail);

        // Badge text: 1-based index
        holder.tvBadge.setText("#" + (position + 1));

        // Remove button
        holder.btnRemove.setOnClickListener(v -> {
            int adapterPos = holder.getAdapterPosition();
            if (adapterPos != RecyclerView.NO_ID) {
                removeListener.onRemove(adapterPos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return imagePaths.size();
    }

    static class ImageViewHolder extends RecyclerView.ViewHolder {
        ImageView imgThumbnail;
        TextView tvBadge;
        ImageButton btnRemove;

        ImageViewHolder(@NonNull View itemView) {
            super(itemView);
            imgThumbnail = itemView.findViewById(R.id.imgThumbnail);
            tvBadge      = itemView.findViewById(R.id.tvBadge);
            btnRemove    = itemView.findViewById(R.id.btnRemove);
        }
    }
}
