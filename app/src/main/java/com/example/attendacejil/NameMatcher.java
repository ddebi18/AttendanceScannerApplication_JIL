package com.example.attendacejil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class NameMatcher {

    public static class DbMember {
        public String id;
        public String firstName;
        public String lastName;
        public String networkName;

        public DbMember(String id, String firstName, String lastName, String networkName) {
            this.id = id;
            this.firstName = firstName;
            this.lastName = lastName;
            this.networkName = networkName;
        }
    }

    /**
     * Calculates the Levenshtein distance between two strings.
     */
    public static int levenshtein(String a, String b) {
        a = a.toLowerCase();
        b = b.toLowerCase();
        int[] costs = new int[b.length() + 1];
        for (int j = 0; j < costs.length; j++)
            costs[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= b.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]),
                        a.charAt(i - 1) == b.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[b.length()];
    }

    /**
     * Corrects scanned rows using the provided database members.
     * Replaces last_name and first_name if a close match is found.
     * Also auto-assigns the network if available.
     */
    public static void autoCorrectNames(JSONArray scannedRows, List<DbMember> dbMembers) {
        if (dbMembers == null || dbMembers.isEmpty() || scannedRows == null) return;

        for (int i = 0; i < scannedRows.length(); i++) {
            JSONObject row = scannedRows.optJSONObject(i);
            if (row == null) continue;

            String scanLast = row.optString("last_name", "").trim();
            String scanFirst = row.optString("first_name", "").trim();
            
            if (scanLast.isEmpty() && scanFirst.isEmpty()) continue;

            DbMember bestMatch = null;
            int bestDistance = Integer.MAX_VALUE;

            for (DbMember dbm : dbMembers) {
                // Heuristic: compare combination or separate.
                int distLast = levenshtein(scanLast, dbm.lastName);
                int distFirst = levenshtein(scanFirst, dbm.firstName);
                
                int totalDist = distLast + distFirst;
                
                // Allowable threshold: e.g., 2 or 3 edits total
                if (totalDist < bestDistance && totalDist <= 3) {
                    bestDistance = totalDist;
                    bestMatch = dbm;
                }
            }

            if (bestMatch != null) {
                try {
                    row.put("db_id", bestMatch.id);
                    row.put("last_name", bestMatch.lastName);
                    row.put("first_name", bestMatch.firstName);
                    if (bestMatch.networkName != null && !bestMatch.networkName.isEmpty()) {
                        row.put("network", bestMatch.networkName);
                    }
                    // Since it matched DB, we can unflag it if we want
                    row.put("flagged", false);
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }
}
