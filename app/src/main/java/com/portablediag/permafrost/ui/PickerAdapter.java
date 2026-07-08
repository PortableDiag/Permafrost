package com.portablediag.permafrost.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.portablediag.permafrost.R;
import com.portablediag.permafrost.core.InstalledApps;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

class PickerAdapter extends RecyclerView.Adapter<PickerAdapter.VH> {

    private final Context ctx;
    private final List<InstalledApps.Entry> full = new ArrayList<>();
    private final List<InstalledApps.Entry> items = new ArrayList<>();
    private final Set<String> checked = new LinkedHashSet<>();
    private String query = "";

    PickerAdapter(Context ctx) {
        this.ctx = ctx;
    }

    void submit(List<InstalledApps.Entry> apps) {
        full.clear();
        full.addAll(apps);
        checked.clear();
        applyFilter();
    }

    /** Narrow the visible list to apps whose label or package matches the query.
     *  Selections (checked) are keyed by package, so they survive filtering. */
    void filter(String q) {
        query = q == null ? "" : q.trim().toLowerCase();
        applyFilter();
    }

    private void applyFilter() {
        items.clear();
        if (query.isEmpty()) {
            items.addAll(full);
        } else {
            for (InstalledApps.Entry e : full) {
                String label = e.label != null ? e.label : e.pkg;
                if (label.toLowerCase().contains(query)
                        || e.pkg.toLowerCase().contains(query)) {
                    items.add(e);
                }
            }
        }
        notifyDataSetChanged();
    }

    List<String> selected() {
        return new ArrayList<>(checked);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_pick_app, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        InstalledApps.Entry e = items.get(position);
        h.label.setText(e.label);
        h.pkg.setText(e.pkg);
        h.icon.setImageDrawable(InstalledApps.iconOf(ctx, e.pkg));
        h.check.setOnCheckedChangeListener(null);
        h.check.setChecked(checked.contains(e.pkg));
        View.OnClickListener toggle = v -> {
            boolean now = !checked.contains(e.pkg);
            if (now) checked.add(e.pkg);
            else checked.remove(e.pkg);
            h.check.setChecked(now);
        };
        h.itemView.setOnClickListener(toggle);
        h.check.setOnClickListener(toggle);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView pkg;
        final MaterialCheckBox check;

        VH(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            pkg = v.findViewById(R.id.pkg);
            check = v.findViewById(R.id.check);
        }
    }
}
