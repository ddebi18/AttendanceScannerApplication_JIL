import sys
import numpy as np
import pytesseract
from PIL import Image as PILImage
import cv2
import app

def test_image(img_path):
    with open(img_path, "rb") as f:
        img_bytes = f.read()

    # Disable Gemini to force fallback
    app.GEMINI_API_KEY = "FAKE"
    app.GEMINI_URL = "http://localhost:9999/fake"
    
    enhanced_bytes = app._enhance_for_gemini(img_bytes)
    bgr = app._decode_image(enhanced_bytes)
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    binary = gray
    att_x0 = app._find_att_split(binary)
    ts_binary = binary[:, :att_x0]
    
    inv_ts = cv2.bitwise_not(ts_binary)
    vert_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (1, 40))
    v_lines = cv2.morphologyEx(inv_ts, cv2.MORPH_OPEN, vert_kernel)
    ts_clean = cv2.bitwise_or(ts_binary, v_lines)
    
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
        lo, hi   = att_w * lo_frac, att_w * hi_frac
        region_x = [x for x in all_x if lo <= x <= hi]
        if not region_x:
            return int(att_w * fallback_frac)
        hist, edges = np.histogram(region_x, bins=20)
        min_bin = int(np.argmin(hist))
        return int((edges[min_bin] + edges[min_bin + 1]) / 2)

    # Let's widen the search region for the gap since columns can be weird!
    # Last Name column is anywhere from 15% to 35%
    x_col1 = _gap_boundary(all_x, 0.15, 0.35, 0.28, att_x0)
    # Network column starts anywhere from 50% to 75%
    x_col2 = _gap_boundary(all_x, 0.50, 0.75, 0.60, att_x0)
    
    print(f"att_x0={att_x0}")
    print(f"x_col1={x_col1} ({(x_col1/att_x0)*100:.1f}%)")
    print(f"x_col2={x_col2} ({(x_col2/att_x0)*100:.1f}%)")
    
    for t in tokens:
        if "BERNADITH" in t["text"] or "VILLANUEVA" in t["text"]:
            print(f"Token: '{t['text']}' at x={t['x']}")
            
if __name__ == "__main__":
    test_image("../SampleOutput/16.jpg")
