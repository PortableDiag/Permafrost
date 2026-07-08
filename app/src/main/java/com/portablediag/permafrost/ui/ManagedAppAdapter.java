package com.portablediag.permafrost.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.portablediag.permafrost.R;
import com.portablediag.permafrost.core.IconFrost;
import com.portablediag.permafrost.core.InstalledApps;
import com.portablediag.permafrost.model.ManagedApp;
import com.portablediag.permafrost.model.Mode;

import java.util.ArrayList;
import java.util.List;

class ManagedAppAdapter extends RecyclerView.Adapter<ManagedAppAdapter.VH> {

    interface OnClick {
        void onClick(ManagedApp app);
    }

    private final Context ctx;
    private final OnClick onClick;
    private final List<ManagedApp> full = new ArrayList<>();
    private final List<ManagedApp> items = new ArrayList<>();
    private String query = "";

    ManagedAppAdapter(Context ctx, OnClick onClick) {
        this.ctx = ctx;
        this.onClick = onClick;
    }

    void submit(List<ManagedApp> apps) {
        full.clear();
        full.addAll(apps);
        applyFilter();
    }

    /** Narrow the visible list to apps whose label or package matches the query. */
    void filter(String q) {
        query = q == null ? "" : q.trim().toLowerCase();
        applyFilter();
    }

    private void applyFilter() {
        items.clear();
        if (query.isEmpty()) {
            items.addAll(full);
        } else {
            for (ManagedApp a : full) {
                String label = a.label != null ? a.label : a.packageName;
                if (label.toLowerCase().contains(query)
                        || a.packageName.toLowerCase().contains(query)) {
                    items.add(a);
                }
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_managed_app, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ManagedApp a = items.get(position);
        h.label.setText(a.label != null ? a.label : a.packageName);

        String state;
        if (a.updateUnlocked) {
            state = ctx.getString(R.string.state_update_unlocked);
        } else if (a.mode == Mode.GHOST) {
            state = ctx.getString(a.dormant ? R.string.state_ghosted : R.string.state_awake);
        } else {
            state = ctx.getString(a.dormant ? R.string.state_frozen : R.string.state_awake);
        }
        String mode = ctx.getString(a.mode == Mode.GHOST ? R.string.mode_ghost : R.string.mode_freeze);
        h.subtitle.setText(mode + " · " + state);

        // Prefer the live app icon; fall back to cached frosted icon (ghosted apps).
        android.graphics.drawable.Drawable icon = InstalledApps.iconOf(ctx, a.packageName);
        if (icon != null) {
            h.icon.setImageDrawable(icon);
        } else {
            h.icon.setImageBitmap(IconFrost.loadCached(ctx, a.packageName));
        }

        h.itemView.setOnClickListener(v -> onClick.onClick(a));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView subtitle;

        VH(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            subtitle = v.findViewById(R.id.subtitle);
        }
    }
}
