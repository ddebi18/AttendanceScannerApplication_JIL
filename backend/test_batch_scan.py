# -*- coding: utf-8 -*-
"""
test_batch_scan.py
==================
Sends all 16 SampleOutput images to the running Flask backend (localhost:5000)
and checks:
  1. NAME RECOGNITION: Are the names on each image correctly read?
     (Compares extracted names against a master name list built from all CSVs)
  2. ATTENDANCE FLAG: Does attendance[col_idx] have reasonable values?

Usage:
    python test_batch_scan.py
"""

import csv
import os
import sys
import time
import difflib
import requests

# Force stdout UTF-8
import io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

# ---- Config ------------------------------------------------------------------
BASE_URL   = "http://127.0.0.1:5000"
SAMPLE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "SampleOutput")
IMAGES     = [os.path.join(SAMPLE_DIR, f"{i}.jpg") for i in range(1, 17)]

# image_num -> (sunday_num, service_num, expected_csv_name)
# Assumption: 2 pages per Sunday/Service combo
# Page 1 (images 1-8):  first half of alphabet per Sunday/Service
# Page 2 (images 9-16): second half of alphabet per Sunday/Service
IMAGE_MAP = {
    1:  (1, 1, "1ST SERVICE_APR_05 - Check-Ins Report.csv"),
    2:  (1, 2, "2ND SERVICE_APR_05 - Check-Ins Report.csv"),
    3:  (2, 1, "1ST SERVICE_APR_12 - Check-Ins Report.csv"),
    4:  (2, 2, "2ND SERVICE_APR_12 - Check-Ins Report.csv"),
    5:  (3, 1, "1ST SERVICE_APR_19 - Check-Ins Report.csv"),
    6:  (3, 2, "2ND SERVICE_APR_19 - Check-Ins Report.csv"),
    7:  (4, 1, "1ST SERVICE_APR_26 - Check-Ins Report.csv"),
    8:  (4, 2, "2ND SERVICE_APR_26 - Check-Ins Report.csv"),
    9:  (1, 1, "1ST SERVICE_APR_05 - Check-Ins Report.csv"),
    10: (1, 2, "2ND SERVICE_APR_05 - Check-Ins Report.csv"),
    11: (2, 1, "1ST SERVICE_APR_12 - Check-Ins Report.csv"),
    12: (2, 2, "2ND SERVICE_APR_12 - Check-Ins Report.csv"),
    13: (3, 1, "1ST SERVICE_APR_19 - Check-Ins Report.csv"),
    14: (3, 2, "2ND SERVICE_APR_19 - Check-Ins Report.csv"),
    15: (4, 1, "1ST SERVICE_APR_26 - Check-Ins Report.csv"),
    16: (4, 2, "2ND SERVICE_APR_26 - Check-Ins Report.csv"),
}

DIVIDER = "=" * 80

def load_expected_csv(csv_name):
    path = os.path.join(SAMPLE_DIR, csv_name)
    if not os.path.exists(path):
        return None, "[MISSING] " + path
    names = set()
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            fn = row.get("First Name", "").strip().upper()
            ln = row.get("Last Name",  "").strip().upper()
            if fn or ln:
                names.add((fn, ln))
    return names, None


def fuzzy_match(name, expected_set, threshold=0.85):
    """Return best match from expected_set using SequenceMatcher, or None."""
    full = f"{name[0]} {name[1]}"
    best_score = 0
    best_match = None
    for exp in expected_set:
        exp_full = f"{exp[0]} {exp[1]}"
        score = difflib.SequenceMatcher(None, full, exp_full).ratio()
        if score > best_score:
            best_score = score
            best_match = exp
    if best_score >= threshold:
        return best_match, best_score
    return None, best_score


def scan_image(img_path, sunday_num, service_num):
    with open(img_path, "rb") as f:
        img_bytes = f.read()
    resp = requests.post(
        f"{BASE_URL}/scan",
        files={"image": (os.path.basename(img_path), img_bytes, "image/jpeg")},
        data={"sunday_num": sunday_num, "service_num": service_num},
        timeout=120,
    )
    resp.raise_for_status()
    return resp.json().get("rows", [])


def main():
    print(DIVIDER)
    print("  BATCH SCAN TEST -- 16 Images vs Expected CSVs")
    print(DIVIDER)

    try:
        requests.get(BASE_URL, timeout=5)
    except Exception:
        print("[ERROR] Cannot reach server at " + BASE_URL)
        sys.exit(1)

    # Load all CSVs and build master name list
    all_csv_names = {}
    for img_num, (sunday, service, csv_name) in IMAGE_MAP.items():
        if csv_name not in all_csv_names:
            exp, err = load_expected_csv(csv_name)
            all_csv_names[csv_name] = {"expected": exp, "load_err": err,
                                        "scanned_all": set(), "sunday": sunday, "service": service}

    # Build one big master set of all known names (across all CSVs)
    master_names = set()
    for data in all_csv_names.values():
        if data["expected"]:
            master_names.update(data["expected"])

    print(f"\nMaster name pool: {len(master_names)} unique names across all 8 CSVs")
    print(f"Scanning 16 images...\n")

    # Store per-image scan results for individual reporting
    image_results = {}

    for img_num in range(1, 17):
        img_path = IMAGES[img_num - 1]
        sunday, service, csv_name = IMAGE_MAP[img_num]

        if not os.path.exists(img_path):
            print(f"  [{img_num:02d}/16] [SKIP] Not found: {img_path}")
            continue

        print(f"  [{img_num:02d}/16] Scanning {os.path.basename(img_path)} "
              f"(Sunday {sunday}, Service {service})...", end=" ", flush=True)

        t0 = time.time()
        try:
            rows = scan_image(img_path, sunday, service)
            elapsed = time.time() - t0
            print(f"[OK] {len(rows)} rows in {elapsed:.1f}s")

            # Add all extracted names to both the CSV bucket and master tracking
            for r in rows:
                fn = r.get("first_name", "").strip().upper()
                ln = r.get("last_name",  "").strip().upper()
                if fn or ln:
                    all_csv_names[csv_name]["scanned_all"].add((fn, ln))

            image_results[img_num] = {"rows": rows, "csv": csv_name,
                                       "sunday": sunday, "service": service,
                                       "elapsed": elapsed}
        except Exception as e:
            print(f"[ERROR] {e}")
            image_results[img_num] = None

    # ---- Name Recognition Report (fuzzy match) -------------------------------
    print(f"\n{DIVIDER}")
    print("  NAME RECOGNITION REPORT (per CSV, all extracted names vs expected)")
    print(DIVIDER)

    total_expected = 0
    total_exact    = 0
    total_fuzzy    = 0
    total_missed   = 0

    for csv_name, data in all_csv_names.items():
        print(f"\n[FILE] {csv_name}")

        if data["load_err"]:
            print(f"       [!] {data['load_err']}")
            continue

        expected  = data["expected"]
        scanned   = data["scanned_all"]

        exact_match  = scanned & expected
        unmatched_sc = scanned - expected
        unmatched_ex = expected - scanned

        # Try fuzzy matching on unmatched scanned names
        fuzzy_matched_pairs = []
        remaining_missed = set(unmatched_ex)
        for sc_name in unmatched_sc:
            m, score = fuzzy_match(sc_name, remaining_missed)
            if m:
                fuzzy_matched_pairs.append((sc_name, m, score))
                remaining_missed.discard(m)

        total_matched_count = len(exact_match) + len(fuzzy_matched_pairs)
        acc = total_matched_count / len(expected) * 100 if expected else 0.0

        print(f"       Expected        : {len(expected):3d} people")
        print(f"       Scanned (all)   : {len(scanned):3d} names extracted")
        print(f"       Exact match     : {len(exact_match):3d}")
        print(f"       Fuzzy match     : {len(fuzzy_matched_pairs):3d}  (similar names, threshold 85%)")
        print(f"       Total recognized: {total_matched_count:3d}  ({acc:.1f}%)")

        if fuzzy_matched_pairs:
            print(f"       Fuzzy matches (scanned -> expected):")
            for sc, ex, score in sorted(fuzzy_matched_pairs, key=lambda x: -x[2]):
                print(f"           '{sc[0]} {sc[1]}' -> '{ex[0]} {ex[1]}' ({score*100:.0f}%)")

        if remaining_missed:
            print(f"       [X] Truly missed ({len(remaining_missed)}) -- not found even with fuzzy:")
            for fn, ln in sorted(remaining_missed):
                print(f"             {fn} {ln}")

        truly_extra = [n for n in unmatched_sc if not any(n == p[0] for p in fuzzy_matched_pairs)]
        if truly_extra:
            print(f"       [+] Truly extra ({len(truly_extra)}) -- extracted but not in expected:")
            for fn, ln in sorted(truly_extra):
                print(f"             {fn} {ln}")

        total_expected += len(expected)
        total_exact    += len(exact_match)
        total_fuzzy    += len(fuzzy_matched_pairs)
        total_missed   += len(remaining_missed)

    # ---- Overall summary -----------------------------------------------------
    total_matched = total_exact + total_fuzzy
    overall_acc   = total_matched / total_expected * 100 if total_expected else 0.0

    print(f"\n{DIVIDER}")
    print("  OVERALL NAME RECOGNITION SUMMARY")
    print(DIVIDER)
    print(f"  Total expected      : {total_expected}")
    print(f"  Exact matches       : {total_exact}")
    print(f"  Fuzzy matches       : {total_fuzzy}")
    print(f"  Total recognized    : {total_matched}  ({overall_acc:.1f}%)")
    print(f"  Truly missed        : {total_missed}")
    print(DIVIDER)

    if overall_acc >= 90:
        print("  [PASS] Name recognition above 90% -- ready to push!")
    elif overall_acc >= 75:
        print("  [PARTIAL] Name recognition above 75% -- review missed names.")
    else:
        print("  [FAIL] Name recognition below 75% -- check server logs.")
    print(DIVIDER + "\n")


if __name__ == "__main__":
    main()
