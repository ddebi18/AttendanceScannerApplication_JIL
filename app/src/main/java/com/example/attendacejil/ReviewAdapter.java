package com.example.attendacejil;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Context;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * ReviewAdapter — RecyclerView adapter for the review/edit table.
 *
 * Each row shows: Last Name | First Name | Network | 10 attendance toggles | Delete
 *
 * Tapping any text cell → inline edit dialog
 * Tapping any attendance button → toggles present/absent
 * Tapping Delete → confirm dialog → marks row for deletion
 *
 * Row background:
 *   flagged      → yellow   (#33FFEB3B)
 *   manuallyAdded→ blue     (#221E88E5)
 *   markedDeleted→ red      (#44E53935)
 *   normal       → transparent
 */
public class ReviewAdapter extends RecyclerView.Adapter<ReviewAdapter.RowViewHolder> {

    public interface OnDataChangedListener {
        void onDataChanged();
    }

    /** Fired when the user edits a name or network — for propagating to all tabs. */
    public interface OnRowEditedListener {
        void onRowEditedGlobally(String id, String newLastName, String newFirstName, String newNetwork);
        void onRowAddedGlobally(String id, String lastName, String firstName, String network);
    }

    private final Context              context;
    private final List<AttendanceRow>  rows;
    private final OnDataChangedListener listener;
    private final int                  selectedColumn;  // 0-9: which attendance col to show
    private OnRowEditedListener        rowEditedListener;

    // IDs of the 10 attendance ImageButtons in item_review_row
    private static final int[] ATT_IDS = {
        R.id.cbAtt0, R.id.cbAtt1, R.id.cbAtt2, R.id.cbAtt3, R.id.cbAtt4,
        R.id.cbAtt5, R.id.cbAtt6, R.id.cbAtt7, R.id.cbAtt8, R.id.cbAtt9
    };

    public ReviewAdapter(Context context,
                         List<AttendanceRow> rows,
                         OnDataChangedListener listener,
                         int selectedColumn) {
        this.context        = context;
        this.rows           = rows;
        this.listener       = listener;
        this.selectedColumn = Math.max(-1, Math.min(9, selectedColumn));
    }

    public void setOnRowEditedListener(OnRowEditedListener l) {
        this.rowEditedListener = l;
    }

    @NonNull
    @Override
    public RowViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context)
                .inflate(R.layout.item_review_row, parent, false);
        return new RowViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RowViewHolder h, int position) {
        AttendanceRow row = rows.get(position);

        // ── Text cells ────────────────────────────────────────────────────────
        bindText(h.tvLastName,  row.lastName,  row, 0 /* last */, position);
        bindText(h.tvFirstName, row.firstName, row, 1 /* first */, position);
        bindText(h.tvNetwork,   row.network,   row, 2 /* net */,   position);

        // Strikethrough for deleted rows
        int flags = row.markedForDeletion
                ? Paint.STRIKE_THRU_TEXT_FLAG : 0;
        h.tvLastName .setPaintFlags(flags);
        h.tvFirstName.setPaintFlags(flags);
        h.tvNetwork  .setPaintFlags(flags);

        // ── Row background ────────────────────────────────────────────────────
        int bgColor;
        if (row.markedForDeletion) {
            bgColor = ContextCompat.getColor(context, R.color.row_deleted);
        } else if (row.flagged) {
            bgColor = ContextCompat.getColor(context, R.color.row_flagged);
        } else if (row.manuallyAdded) {
            bgColor = ContextCompat.getColor(context, R.color.row_manual);
        } else {
            bgColor = android.graphics.Color.TRANSPARENT;
        }
        h.rowContainer.setBackgroundColor(bgColor);

        // ── Attendance toggle — show ONLY the selected column ─────────────────
        for (int i = 0; i < ATT_IDS.length; i++) {
            if (i != selectedColumn) {
                h.attBtns[i].setVisibility(View.GONE);
                continue;
            }
            h.attBtns[i].setVisibility(View.VISIBLE);
            final int idx = i;
            boolean present = (row.attendance != null && idx < row.attendance.length)
                    && row.attendance[idx];
            h.attBtns[i].setImageResource(present
                    ? android.R.drawable.checkbox_on_background
                    : android.R.drawable.checkbox_off_background);
            h.attBtns[i].setColorFilter(present ? 0xFF4CAF50 : 0xFF555566);
            h.attBtns[i].setOnClickListener(v -> {
                if (row.markedForDeletion) return;
                row.attendance[idx] = !row.attendance[idx];
                notifyItemChanged(h.getAdapterPosition());
                listener.onDataChanged();
            });
        }

        // ── Delete button ─────────────────────────────────────────────────────
        h.btnDelete.setOnClickListener(v -> {
            int pos = h.getAdapterPosition();
            if (pos == RecyclerView.NO_ID) return;
            if (row.markedForDeletion) {
                // Undo delete
                row.markedForDeletion = false;
                notifyItemChanged(pos);
                listener.onDataChanged();
            } else {
                new MaterialAlertDialogBuilder(context, R.style.AttendanceDialogTheme)
                        .setTitle("Delete row?")
                        .setMessage(row.lastName + ", " + row.firstName)
                        .setPositiveButton("Delete", (d, w) -> {
                            row.markedForDeletion = true;
                            notifyItemChanged(pos);
                            listener.onDataChanged();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
    }

    // ── Inline text edit ──────────────────────────────────────────────────────

    /**
     * field: 0=lastName, 1=firstName, 2=network
     */
    private void bindText(TextView tv, String text,
                          AttendanceRow row, int field, int position) {
        tv.setText(text);
        tv.setOnClickListener(v -> {
            if (row.markedForDeletion) return;
            EditText et = new EditText(context);
            et.setText(text);
            et.setTextColor(0xFFFFFFFF);
            et.setHintTextColor(0xFF9E9E9E);
            et.setBackgroundTintList(
                    ContextCompat.getColorStateList(context, R.color.accent_blue));

            String title = field == 0 ? "Last Name"
                         : field == 1 ? "First Name"
                         :              "Network";
            new MaterialAlertDialogBuilder(context, R.style.AttendanceDialogTheme)
                    .setTitle("Edit " + title)
                    .setView(et)
                    .setPositiveButton("Save", (d, w) -> {
                        String val = et.getText().toString().trim().toUpperCase()
                                        .replace("ñ", "n").replace("Ñ", "N");
                        if (field == 0) { row.lastName  = val; }
                        else if (field == 1) { row.firstName = val; }
                        else { row.network = val; }
                        // Clear flagged if user corrected something
                        if (!val.isEmpty()) {
                            row.flagged = !row.isValid()
                                    || row.lastNameConf  < 75
                                    || row.firstNameConf < 75
                                    || row.networkConf   < 75;
                            // If user manually fixed all text, clear flag
                            if (!row.lastName.isEmpty()
                                    && !row.firstName.isEmpty()
                                    && !row.network.isEmpty()) {
                                row.flagged = false;
                            }
                        }
                        notifyItemChanged(position);
                        listener.onDataChanged();
                        // Notify global listener so other tabs can sync
                        if (rowEditedListener != null) {
                            rowEditedListener.onRowEditedGlobally(row.id, row.lastName, row.firstName, row.network);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    @Override
    public int getItemCount() { return rows.size(); }

    public List<AttendanceRow> getRows() {
        return rows;
    }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    static class RowViewHolder extends RecyclerView.ViewHolder {
        LinearLayout  rowContainer;
        TextView      tvLastName, tvFirstName, tvNetwork;
        ImageButton[] attBtns = new ImageButton[10];
        ImageButton   btnDelete;

        RowViewHolder(@NonNull View itemView) {
            super(itemView);
            rowContainer = itemView.findViewById(R.id.rowContainer);
            tvLastName   = itemView.findViewById(R.id.tvLastName);
            tvFirstName  = itemView.findViewById(R.id.tvFirstName);
            tvNetwork    = itemView.findViewById(R.id.tvNetwork);
            btnDelete    = itemView.findViewById(R.id.btnDeleteRow);
            for (int i = 0; i < ATT_IDS.length; i++) {
                attBtns[i] = itemView.findViewById(ATT_IDS[i]);
            }
        }

        private static final int[] ATT_IDS = {
            R.id.cbAtt0, R.id.cbAtt1, R.id.cbAtt2, R.id.cbAtt3, R.id.cbAtt4,
            R.id.cbAtt5, R.id.cbAtt6, R.id.cbAtt7, R.id.cbAtt8, R.id.cbAtt9
        };
    }
}
