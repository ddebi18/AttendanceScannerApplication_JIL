package com.example.attendacejil;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class BatchImageAdapter extends RecyclerView.Adapter<BatchImageAdapter.ViewHolder> {

    private final Context context;
    private final List<Uri> uris;
    private final Runnable onChange;

    public BatchImageAdapter(Context context, List<Uri> uris, Runnable onChange) {
        this.context = context;
        this.uris = uris;
        this.onChange = onChange;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_batch_image, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Uri uri = uris.get(position);
        com.bumptech.glide.Glide.with(context)
                .load(uri)
                .centerCrop()
                .into(holder.ivPreview);
        holder.tvIndex.setText(String.valueOf(position + 1));
        
        holder.ivPreview.setOnClickListener(v -> {
            android.app.Dialog dialog = new android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            ImageView iv = new ImageView(context);
            iv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            com.bumptech.glide.Glide.with(context).load(uri).fitCenter().into(iv);
            dialog.setContentView(iv);
            iv.setOnClickListener(v2 -> dialog.dismiss());
            dialog.show();
        });
        
        holder.btnRemove.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_ID) {
                uris.remove(pos);
                notifyItemRemoved(pos);
                notifyItemRangeChanged(pos, uris.size());
                onChange.run();
            }
        });
    }

    @Override
    public int getItemCount() {
        return uris.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivPreview;
        ImageButton btnRemove;
        TextView tvIndex;

        ViewHolder(View view) {
            super(view);
            ivPreview = view.findViewById(R.id.ivPreview);
            btnRemove = view.findViewById(R.id.btnRemove);
            tvIndex = view.findViewById(R.id.tvIndex);
        }
    }
}
