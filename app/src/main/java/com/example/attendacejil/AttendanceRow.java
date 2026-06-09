package com.example.attendacejil;

import java.io.Serializable;
import java.util.Arrays;

/**
 * AttendanceRow — data model for a single member row.
 *
 * Fields mirror the backend /scan JSON response.
 * Implements Serializable so it can be passed between Activities via Intent extras.
 */
public class AttendanceRow implements Serializable {

    // ── Identity ───────────────────────────────────────────────────────────────
    public String lastName;
    public String firstName;
    public String network;

    // ── OCR confidence (0-100) ────────────────────────────────────────────────
    public int lastNameConf;
    public int firstNameConf;
    public int networkConf;

    // ── Attendance: attendance[0] = Sunday 1 Service 1 … [9] = Sunday 5 Svc 2 ─
    public boolean[] attendance;  // length 10

    // ── Review state ──────────────────────────────────────────────────────────
    /** True if any OCR confidence was below threshold — highlight yellow. */
    public boolean flagged;
    /** True if this row was added manually by the user — highlight blue. */
    public boolean manuallyAdded;
    /** True if user has marked this row for deletion — show red strikethrough. */
    public boolean markedForDeletion;

    /** Default constructor for manually added rows. */
    public AttendanceRow() {
        lastName      = "";
        firstName     = "";
        network       = "";
        lastNameConf  = 100;
        firstNameConf = 100;
        networkConf   = 100;
        attendance    = new boolean[10];
        Arrays.fill(attendance, false);
        flagged           = false;
        manuallyAdded     = true;
        markedForDeletion = false;
    }

    /**
     * Constructor from parsed JSON fields.
     */
    public AttendanceRow(
            String lastName, int lastNameConf,
            String firstName, int firstNameConf,
            String network, int networkConf,
            boolean[] attendance,
            boolean flagged) {
        this.lastName       = lastName;
        this.lastNameConf   = lastNameConf;
        this.firstName      = firstName;
        this.firstNameConf  = firstNameConf;
        this.network        = network;
        this.networkConf    = networkConf;
        this.attendance     = attendance != null ? attendance : new boolean[10];
        this.flagged        = flagged;
        this.manuallyAdded  = false;
        this.markedForDeletion = false;
    }

    /** Return true if this row passes all export validation rules. */
    public boolean isValid() {
        return !lastName.trim().isEmpty()
                && !firstName.trim().isEmpty()
                && !network.trim().isEmpty()
                && !flagged;
    }

    /** The attendance column index for a given sunday (1-5) and service (1-2). */
    public static int attendanceIndex(int sunday, int service) {
        return (sunday - 1) * 2 + (service - 1);
    }

    /** Count how many services this member attended. */
    public int totalAttended() {
        int n = 0;
        for (boolean b : attendance) if (b) n++;
        return n;
    }
}
