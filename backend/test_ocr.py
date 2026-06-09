import cv2
import sys
import numpy as np
import pytesseract
from PIL import Image as PILImage
import app

def test_image(img_path):
    print(f"Testing {img_path}...")
    with open(img_path, "rb") as f:
        img_bytes = f.read()

    bgr = app._decode_image(img_bytes)
    bgr = app._auto_orient(bgr)
    bgr = app._resize(bgr)
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    enhanced = clahe.apply(gray)
    filt = cv2.bilateralFilter(enhanced, 9, 75, 75)
    binary = cv2.adaptiveThreshold(filt, 255,
                                     cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                     cv2.THRESH_BINARY, 21, 8)
                                     
    att_x0 = app._find_att_split(binary)
    ts_binary = binary[:, :att_x0]
    
    inv_ts = cv2.bitwise_not(ts_binary)
    vert_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (1, 40))
    v_lines = cv2.morphologyEx(inv_ts, cv2.MORPH_OPEN, vert_kernel)
    ts_clean = cv2.bitwise_or(ts_binary, v_lines)
    
    # NEW: Draw thick white lines at 33% and 66% to FORCE Tesseract to split words!
    x1 = int(att_x0 * 0.33)
    x2 = int(att_x0 * 0.66)
    cv2.line(ts_clean, (x1, 0), (x1, ts_clean.shape[0]), 255, 6)
    cv2.line(ts_clean, (x2, 0), (x2, ts_clean.shape[0]), 255, 6)

    scale = 2
    ts_up = cv2.resize(ts_clean,
                       (ts_clean.shape[1] * scale, ts_clean.shape[0] * scale),
                       interpolation=cv2.INTER_LANCZOS4)

    pil_img = PILImage.fromarray(ts_up)
    data = pytesseract.image_to_data(
        pil_img,
        config="--psm 11 --oem 3 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz ",
        output_type=pytesseract.Output.DICT
    )
    
    tokens = []
    for i, txt in enumerate(data["text"]):
        if not txt.strip(): continue
        if str(data["conf"][i]).strip() in ("-1", ""): continue
        tokens.append({
            "text": txt.strip().upper(),
            "x": data["left"][i] / scale,
            "y": data["top"][i] / scale,
            "w": data["width"][i] / scale,
            "h": data["height"][i] / scale
        })

    all_x = [t["x"] for t in tokens]
    def _gap_boundary(all_x, lo_frac, hi_frac, fallback_frac, att_w):
        lo, hi = att_w * lo_frac, att_w * hi_frac
        region_x = [x for x in all_x if lo <= x <= hi]
        if not region_x: return int(att_w * fallback_frac)
        hist, edges = np.histogram(region_x, bins=20)
        min_bin = int(np.argmin(hist))
        return int((edges[min_bin] + edges[min_bin + 1]) / 2)

    x_col1 = _gap_boundary(all_x, 0.28, 0.52, 0.42, att_x0)
    x_col2 = _gap_boundary(all_x, 0.62, 0.90, 0.80, att_x0)
    
    # Step 4: cluster tokens into rows by Y-position
    median_h = float(np.median([t["h"] for t in tokens if t["h"] > 0]))
    y_tol    = max(median_h * 0.6, 5)
    tokens.sort(key=lambda t: (t["y"], t["x"]))
    row_groups = []
    for tok in tokens:
        placed = False
        for grp in row_groups:
            if abs(tok["y"] - grp[0]["y"]) <= y_tol:
                grp.append(tok)
                placed = True
                break
        if not placed:
            row_groups.append([tok])
            
    rows_out = []
    for grp in row_groups:
        grp.sort(key=lambda t: t["x"])
        last_toks  = [t for t in grp if t["x"] < x_col1]
        first_toks = [t for t in grp if x_col1 <= t["x"] < x_col2]
        net_toks   = [t for t in grp if t["x"] >= x_col2]

        last_name   = " ".join(t["text"] for t in last_toks).strip()
        first_name  = " ".join(t["text"] for t in first_toks).strip()
        net         = " ".join(t["text"] for t in net_toks).strip()
        
        print(f"LAST: '{last_name}' FIRST: '{first_name}' NET: '{net}'")

if __name__ == "__main__":
    test_image("../SampleOutput/1.jpg")
