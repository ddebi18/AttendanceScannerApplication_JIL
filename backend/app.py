"""
app.py — Flask Attendance Scanner Backend  v2.0
================================================
Jesus Is Lord Church Antipolo — Full Pipeline

Endpoints
─────────
POST /scan
  multipart/form-data:  image (single file), sunday_num, service_num
  Returns JSON:
  {
    "rows": [
      {
        "last_name": "TINAPAN", "last_name_conf": 92,
        "first_name": "RENALDO", "first_name_conf": 88,
        "network": "MEN",       "network_conf": 95,
        "flagged": false,
        "attendance": [false, false, true, false, false,
                       false, false, false, false, false]
      }, ...
    ],
    "flagged_count": 3,
    "total_rows": 45
  }

POST /export
  application/json:  { sunday_num, service_num, month, year, rows[] }
  Returns: text/csv attachment
    JIL_Antipolo_Attendance_{MONTH}_{YEAR}.csv

POST /upload  (legacy — kept for backward compat)
  Returns CSV directly without review step.
"""

import base64
import io
import json
import logging
import math
import os
import time
import traceback
from datetime import datetime

# Load .env file if present (GEMINI_API_KEY etc.)
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

import threading

import cv2
import numpy as np
import pandas as pd
import requests as http_requests
from flask import Flask, request, jsonify, send_file

# ── Optional OCR (pytesseract) ────────────────────────────────────────────────
try:
    import pytesseract
    from PIL import Image as PILImage
    # Auto-detect Tesseract binary on Windows
    _tess_paths = [
        r"C:\Program Files\Tesseract-OCR\tesseract.exe",
        r"C:\Program Files (x86)\Tesseract-OCR\tesseract.exe",
    ]
    for _p in _tess_paths:
        if os.path.exists(_p):
            pytesseract.pytesseract.tesseract_cmd = _p
            break
    pytesseract.get_tesseract_version()   # raises if binary not found
    OCR_AVAILABLE = True
    _tess_msg = f"Tesseract ready: {pytesseract.pytesseract.tesseract_cmd}"
except Exception as _e:
    OCR_AVAILABLE = False
    _tess_msg = f"Tesseract not available ({_e})"

# ── Optional easyocr fallback ─────────────────────────────────────────────────
try:
    import easyocr
    _easy_reader = easyocr.Reader(["en"], gpu=False, verbose=False)
    EASYOCR_AVAILABLE = True
except Exception:
    EASYOCR_AVAILABLE = False
    _easy_reader = None

# ── Logging ───────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s"
)
log = logging.getLogger(__name__)
log.info(_tess_msg)  # deferred Tesseract status message

app = Flask(__name__)
app.config['MAX_CONTENT_LENGTH'] = 16 * 1024 * 1024  # 16 MB upload limit

# ─────────────────────────────────────────────────────────────────────────────
# Constants
# ─────────────────────────────────────────────────────────────────────────────

MAX_WIDTH            = 1600   # px — rescale if wider
DARK_PIXEL_RATIO     = 0.08   # checkmark detection threshold
MAX_DARK_RATIO       = 0.50   # above → filled block, not a mark
MIN_CONFIDENCE       = 75     # OCR confidence threshold (0-100)

ALLOWED_NETWORKS = {"MEN", "WOMEN", "KKB", "YAN", "CHILDREN",
                    "MAN", "WOMAN", "KIDS", "YOUTH", "NOT IDENTIFIED"}

SERVICE_KIND = {
    1: "1st Service @ 7:30am",
    2: "2nd Service @ 9:00am",
}

NUM_ATTENDANCE_COLS = 10

# ── Gemini Vision API ─────────────────────────────────────────────────────────
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "")
GEMINI_MODEL   = "gemini-2.5-flash"
GEMINI_URL     = ("https://generativelanguage.googleapis.com/v1beta/models/"
                  f"{GEMINI_MODEL}:generateContent")

# Rate limiter: free tier allows 15 RPM → 1 call per 4 seconds.
# A threading lock ensures back-to-back batch scans queue up instead of 429-ing.
_gemini_lock          = threading.Lock()
_gemini_last_call_ts  = 0.0
_GEMINI_MIN_INTERVAL  = 4.2   # seconds between consecutive Gemini calls
GEMINI_PROMPT_TEMPLATE = """\
You are an OCR assistant. Analyze this church attendance sheet image.

The sheet has these columns (left to right):
  1. LAST NAME
  2. FIRST NAME
  3. NETWORK  (one of: MEN, WOMEN, KKB, YAN, CHILDREN)
  4 onward: 10 attendance tick columns arranged as:
     Column 1: 1st Sunday, 1st Service
     Column 2: 1st Sunday, 2nd Service
     Column 3: 2nd Sunday, 1st Service
     Column 4: 2nd Sunday, 2nd Service
     Column 5: 3rd Sunday, 1st Service
     Column 6: 3rd Sunday, 2nd Service
     Column 7: 4th Sunday, 1st Service
     Column 8: 4th Sunday, 2nd Service
     Column 9: 5th Sunday, 1st Service
     Column 10: 5th Sunday, 2nd Service

We are focusing on column {col_num} ({col_label}), but you MUST scan and return the attendance for ALL 10 columns for every person row.

Extract EVERY person row — their last name, first name, network, and a 10-element list representing their attendance status for each of the 10 columns.

Return ONLY valid JSON — no markdown fences, no explanation:
{{
  "rows": [
    {{
      "last_name": "TINAPAN",
      "first_name": "RENALDO",
      "network": "MEN",
      "attendance": [true, false, true, false, false, false, false, false, false, false]
    }}
  ]
}}

Rules:
- SKIP the header row ("LAST NAME", "FIRST NAME", "NETWORK" labels) and any title rows at the top.
- CRITICAL: Return the rows in the EXACT physical top-to-bottom order as they appear on the page. DO NOT sort them alphabetically.
- Include ALL person data rows — even those with no check marks.
- CRITICAL: The physical document has separate columns for LAST NAME and FIRST NAME. You MUST extract them into separate fields exactly as written. DO NOT concatenate the first name into the last_name field. If a column is blank, leave its field empty.
- The "attendance" list must contain exactly 10 boolean values corresponding to the 10 columns. Set each to true only if there is a visible check mark, tick (✓), slash (/), or any written mark in that column's cell for that person. If the cell is empty, set it to false.
- Use UPPERCASE for all name and network values.
- Do NOT read vertical cell border lines as part of a name. If a name begins with a stray "I", "L", or "J" that is clearly a grid line artifact, drop that leading character.
- Map network values to the closest of: MEN, WOMEN, KKB, YAN, CHILDREN. If truly unclear, use "NOT IDENTIFIED".
"""


# =============================================================================
# IMAGE PROCESSING HELPERS
# =============================================================================

def _resize(image: np.ndarray, max_width: int = MAX_WIDTH) -> np.ndarray:
    h, w = image.shape[:2]
    if w > max_width:
        scale = max_width / w
        image = cv2.resize(image, (max_width, int(h * scale)),
                           interpolation=cv2.INTER_AREA)
    return image


def _decode_image(img_bytes: bytes) -> np.ndarray:
    """Decodes image bytes into a BGR numpy array, applying EXIF rotation if present."""
    from PIL import Image, ImageOps
    try:
        pil_img = Image.open(io.BytesIO(img_bytes))
        pil_img = ImageOps.exif_transpose(pil_img)
        rgb = np.array(pil_img.convert("RGB"))
        return cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
    except Exception as e:
        log.warning("  PIL decode failed: %s. Falling back to cv2.imdecode.", e)
        nparr = np.frombuffer(img_bytes, np.uint8)
        return cv2.imdecode(nparr, cv2.IMREAD_COLOR)


def _auto_orient(image: np.ndarray) -> np.ndarray:
    """Ensure the image is upright. Uses EXIF first (via _decode_image), then OSD if available."""
    h, w = image.shape[:2]
    
    if OCR_AVAILABLE:
        try:
            # Resize down for speed
            scale = 800.0 / max(h, w)
            small = cv2.resize(image, (0, 0), fx=scale, fy=scale)
            # Convert to RGB for pytesseract
            small_rgb = cv2.cvtColor(small, cv2.COLOR_BGR2RGB)
            pil_small = PILImage.fromarray(small_rgb)
            
            osd = pytesseract.image_to_osd(pil_small, output_type=pytesseract.Output.DICT)
            rot = osd.get("rotate", 0)
            
            if rot == 90:
                log.info("  OSD: rotating 90 CW")
                return cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE)
            elif rot == 180:
                log.info("  OSD: rotating 180")
                return cv2.rotate(image, cv2.ROTATE_180)
            elif rot == 270:
                log.info("  OSD: rotating 90 CCW")
                return cv2.rotate(image, cv2.ROTATE_90_COUNTERCLOCKWISE)
            
            log.info("  OSD: image is upright (0 deg).")
            return image
        except Exception as e:
            log.warning("  OSD orientation failed: %s", e)

    # Fallback: if it's landscape, blindly rotate CW
    if w > h:
        log.info("  Fallback: rotating landscape → portrait 90 CW")
        return cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE)
        
    return image


def _find_document_corners(image_bgr: np.ndarray):
    """
    Detect the four corners of the document in the image.
    Returns a 4×2 float32 array [TL, TR, BR, BL], or None if not found.
    """
    gray = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(blurred, 50, 150)
    edges = cv2.dilate(edges, np.ones((3, 3), np.uint8), iterations=1)

    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        return None

    contours = sorted(contours, key=cv2.contourArea, reverse=True)

    for cnt in contours[:5]:
        peri = cv2.arcLength(cnt, True)
        approx = cv2.approxPolyDP(cnt, 0.02 * peri, True)
        if len(approx) == 4:
            pts = approx.reshape(4, 2).astype(np.float32)
            # Order: TL, TR, BR, BL
            s = pts.sum(axis=1)
            diff = np.diff(pts, axis=1)
            ordered = np.array([
                pts[np.argmin(s)],   # TL
                pts[np.argmin(diff)],# TR
                pts[np.argmax(s)],   # BR
                pts[np.argmax(diff)] # BL
            ], dtype=np.float32)
            return ordered

    return None


def _perspective_warp(image_bgr: np.ndarray, corners) -> np.ndarray:
    """
    Apply perspective transform using the 4 detected corners.
    Output is a flat, upright document image.
    """
    tl, tr, br, bl = corners

    width_top    = np.linalg.norm(tr - tl)
    width_bottom = np.linalg.norm(br - bl)
    max_w        = int(max(width_top, width_bottom))

    height_left  = np.linalg.norm(bl - tl)
    height_right = np.linalg.norm(br - tr)
    max_h        = int(max(height_left, height_right))

    dst = np.array([
        [0, 0],
        [max_w - 1, 0],
        [max_w - 1, max_h - 1],
        [0, max_h - 1]
    ], dtype=np.float32)

    M       = cv2.getPerspectiveTransform(corners, dst)
    warped  = cv2.warpPerspective(image_bgr, M, (max_w, max_h))
    return warped


def _deskew(gray: np.ndarray) -> np.ndarray:
    """Correct small remaining skew angle using Hough lines."""
    edges = cv2.Canny(gray, 50, 150, apertureSize=3)
    lines = cv2.HoughLines(edges, 1, np.pi / 180, threshold=100)
    if lines is None:
        return gray

    angles = []
    for line in lines:
        rho, theta = line[0]
        angle = math.degrees(theta) - 90
        if abs(angle) < 45:
            angles.append(angle)

    if not angles:
        return gray

    median_angle = float(np.median(angles))
    if abs(median_angle) < 0.5:
        return gray

    h, w = gray.shape
    M = cv2.getRotationMatrix2D((w // 2, h // 2), median_angle, 1.0)
    return cv2.warpAffine(gray, M, (w, h),
                          flags=cv2.INTER_LINEAR,
                          borderMode=cv2.BORDER_REPLICATE)


def _camscanner_preprocess(image_bgr: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """
    Full CamScanner-style preprocessing pipeline.
    Returns (binary_image, gray_image) both at same resolution.

    Pipeline:
      1. Auto-orient
      2. Resize
      3. Perspective warp ONLY if corners are reliable (>50% image area)
      4. Grayscale
      5. Deskew
      6. CLAHE contrast enhancement
      7. Bilateral filter (noise reduction, edge-preserving)
      8. Adaptive Gaussian threshold (blockSize=21)
      9. Unsharp mask sharpening
    """
    image_bgr = _auto_orient(image_bgr)
    image_bgr = _resize(image_bgr)

    h_orig, w_orig = image_bgr.shape[:2]
    img_area = h_orig * w_orig

    # Perspective correction — skip if corners don't look like a real doc quad.
    # For bound-book scans the largest contour is often the page edge; warping it
    # skews the thin grid lines and breaks morphological detection.
    corners = _find_document_corners(image_bgr)
    if corners is not None:
        quad_area = float(cv2.contourArea(corners))
        if quad_area >= img_area * 0.50:
            try:
                image_bgr = _perspective_warp(image_bgr, corners)
                log.info("  Perspective warp applied (quad=%.0f%% of image).",
                         100 * quad_area / img_area)
            except Exception as e:
                log.warning("  Perspective warp failed: %s", e)
        else:
            log.info("  Perspective warp SKIPPED (quad=%.0f%% < 50%% of image).",
                     100 * quad_area / img_area)

    # Grayscale
    gray = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY) \
           if len(image_bgr.shape) == 3 else image_bgr.copy()

    # Deskew
    gray = _deskew(gray)

    # CLAHE
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    gray  = clahe.apply(gray)

    # Bilateral filter (preserves edges while reducing noise)
    gray_f = cv2.bilateralFilter(gray, d=9, sigmaColor=75, sigmaSpace=75)

    # Adaptive threshold — blockSize=21 preserves thin cell borders better than 31
    binary = cv2.adaptiveThreshold(
        gray_f, 255,
        cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
        cv2.THRESH_BINARY,
        blockSize=21, C=8
    )

    # Unsharp mask sharpening on gray (used for OCR cell crops)
    kernel     = np.array([[0, -1, 0], [-1, 5, -1], [0, -1, 0]])
    gray_sharp = cv2.filter2D(gray_f, -1, kernel)

    log.info("  Preprocessed: binary shape=%s", binary.shape)
    return binary, gray_sharp


# =============================================================================
# GRID DETECTION
# =============================================================================

def _detect_grid_lines_loose(binary: np.ndarray):
    """
    Very permissive fallback: minimal kernel span for faint/broken grid lines.
    Used when the standard pass finds too few lines (e.g. shadow at book gutter).
    """
    h, w = binary.shape
    inv  = cv2.bitwise_not(binary)

    h_span = max(w // 60, 15)
    v_span = max(h // 60, 15)

    horiz_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (h_span, 1))
    horiz = cv2.morphologyEx(inv, cv2.MORPH_OPEN, horiz_kernel)
    horiz = cv2.dilate(horiz, np.ones((5, 1), np.uint8), iterations=3)

    vert_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (1, v_span))
    vert = cv2.morphologyEx(inv, cv2.MORPH_OPEN, vert_kernel)
    vert = cv2.dilate(vert, np.ones((1, 5), np.uint8), iterations=3)

    return horiz, vert


def _detect_grid_lines(binary: np.ndarray):
    """
    Detect horizontal and vertical lines via morphological ops.
    Lines are dark (0) in the THRESH_BINARY image, so invert first.
    """
    h, w = binary.shape
    inv = cv2.bitwise_not(binary)

    # w//30 gives a shorter span requirement than w//20 → catches lines that
    # are partially occluded (bound-book gutter shadow, fingers, etc.)
    h_span = max(w // 30, 25)
    v_span = max(h // 30, 25)

    horiz_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (h_span, 1))
    horiz = cv2.morphologyEx(inv, cv2.MORPH_OPEN, horiz_kernel)
    horiz = cv2.dilate(horiz, np.ones((4, 1), np.uint8), iterations=2)

    vert_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (1, v_span))
    vert = cv2.morphologyEx(inv, cv2.MORPH_OPEN, vert_kernel)
    vert = cv2.dilate(vert, np.ones((1, 4), np.uint8), iterations=2)

    log.info("  Kernels: horiz=%dpx  vert=%dpx", h_span, v_span)
    log.info("  horiz mask nonzero=%d  vert mask nonzero=%d",
             cv2.countNonZero(horiz), cv2.countNonZero(vert))
    return horiz, vert


def _find_sorted_lines(mask: np.ndarray, axis: int, min_gap: int = 10):
    """Project mask along axis → find dominant line positions."""
    projection = np.sum(mask, axis=axis)
    max_proj   = float(projection.max())
    if max_proj == 0:
        return []

    # 0.15 instead of 0.3 — captures weaker/partially broken lines
    threshold = max_proj * 0.15
    peaks     = np.where(projection > threshold)[0]

    if len(peaks) == 0:
        return []

    clusters = []
    cluster  = [peaks[0]]
    for p in peaks[1:]:
        if p - cluster[-1] <= min_gap:
            cluster.append(p)
        else:
            clusters.append(cluster)
            cluster = [p]
    clusters.append(cluster)

    return sorted(int(np.median(c)) for c in clusters)


def _extract_cells(binary: np.ndarray, gray: np.ndarray):
    """
    Return cells[row][col] = (binary_crop, gray_crop).
    Both crops are from the same spatial region.
    Returns None if grid is too small.
    """
    horiz, vert  = _detect_grid_lines(binary)
    h_lines      = _find_sorted_lines(horiz, axis=1)
    v_lines      = _find_sorted_lines(vert,  axis=0)

    log.info("  Grid: %d horizontal × %d vertical lines",
             len(h_lines), len(v_lines))

    if len(h_lines) < 3 or len(v_lines) < 4:
        log.warning("  Insufficient grid (h=%d, v=%d) — trying looser pass.",
                    len(h_lines), len(v_lines))
        # Looser pass: lower threshold even further
        horiz2, vert2 = _detect_grid_lines_loose(binary)
        h_lines2 = _find_sorted_lines(horiz2, axis=1)
        v_lines2 = _find_sorted_lines(vert2,  axis=0)
        log.info("  Loose pass: %d h × %d v lines", len(h_lines2), len(v_lines2))
        if len(h_lines2) >= len(h_lines):
            h_lines = h_lines2
        if len(v_lines2) >= len(v_lines):
            v_lines = v_lines2

    if len(h_lines) < 2 or len(v_lines) < 3:
        log.warning("  Still insufficient grid — aborting cell extraction.")
        return None

    PAD = 3
    cells = []
    for ri in range(len(h_lines) - 1):
        row = []
        y1 = h_lines[ri] + PAD
        y2 = h_lines[ri + 1] - PAD
        for ci in range(len(v_lines) - 1):
            x1 = v_lines[ci]  + PAD
            x2 = v_lines[ci + 1] - PAD
            b_crop = binary[y1:y2, x1:x2] if y2 > y1 and x2 > x1 else None
            g_crop = gray  [y1:y2, x1:x2] if y2 > y1 and x2 > x1 else None
            row.append((b_crop, g_crop))
        cells.append(row)

    return cells


# =============================================================================
# CHECKMARK DETECTION
# =============================================================================

def _has_mark(cell_binary, dark_threshold: float = 0.07) -> bool:
    """
    True if the binary cell contains a hand-drawn slash or checkmark.
    Strips 3px border (grid lines) then checks interior dark-pixel ratio.
    binary is THRESH_BINARY: marks are dark (0) on white (255).
    """
    if cell_binary is None or cell_binary.size == 0:
        return False
    h, w = cell_binary.shape[:2]
    pad = 3
    y1, y2 = pad, max(pad + 1, h - pad)
    x1, x2 = pad, max(pad + 1, w - pad)
    interior = cell_binary[y1:y2, x1:x2]
    if interior.size == 0:
        return False
    dark_ratio = np.sum(interior < 128) / interior.size
    return dark_threshold < dark_ratio < MAX_DARK_RATIO


# =============================================================================
# OCR HELPERS
# =============================================================================

def _ocr_cell(gray_cell) -> tuple[str, int]:
    """
    Run OCR on a single grey cropped cell.
    Returns (text, confidence 0-100).
    Falls back to empty string + confidence=0 if OCR not available.
    """
    if gray_cell is None or gray_cell.size == 0:
        return "", 0

    # Pad cell for better OCR
    padded = cv2.copyMakeBorder(gray_cell, 10, 10, 10, 10,
                                 cv2.BORDER_CONSTANT, value=255)

    if OCR_AVAILABLE:
        try:
            pil_img = PILImage.fromarray(padded)
            data = pytesseract.image_to_data(
                pil_img,
                config="--psm 7 --oem 3 -c tessedit_char_whitelist="
                       "ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz",
                output_type=pytesseract.Output.DICT
            )
            texts = []
            confs = []
            for txt, conf in zip(data["text"], data["conf"]):
                if str(conf).strip() not in ("-1", "") and txt.strip():
                    texts.append(txt.strip())
                    confs.append(int(conf))
            if texts:
                return " ".join(texts).upper().strip(), int(np.mean(confs))
            return "", 0
        except Exception as e:
            log.warning("  pytesseract error: %s", e)

    if EASYOCR_AVAILABLE:
        try:
            results = _easy_reader.readtext(padded, detail=1, paragraph=False)
            if results:
                texts = [r[1] for r in results]
                confs = [int(r[2] * 100) for r in results]
                return " ".join(texts).upper().strip(), int(np.mean(confs))
            return "", 0
        except Exception as e:
            log.warning("  easyocr error: %s", e)

    # No OCR — return positional placeholder
    return "", 0


def _normalize_network(raw: str) -> str:
    """Map raw OCR output to a canonical network name."""
    s = raw.upper().strip()
    MAP = {
        "MAN": "MEN", "MANS": "MEN", "MN": "MEN",
        "WOMAN": "WOMEN", "WOMENS": "WOMEN", "WMN": "WOMEN",
        "KIDS": "CHILDREN", "CHILD": "CHILDREN",
        "YOUTH": "YAN", "YOUNG": "YAN",
        "KKB": "KKB",
    }
    return MAP.get(s, s)


def _is_header_band(binary_strip: np.ndarray) -> bool:
    """True if the strip looks like a dark header (mean brightness < 150)."""
    if binary_strip is None or binary_strip.size == 0:
        return True
    return float(np.mean(binary_strip)) < 150


def _find_row_bands(binary: np.ndarray,
                   min_row_h: int = 6) -> list:
    """
    Find horizontal data-row bands by brightness projection.

    The binary image is THRESH_BINARY (paper=255, text/lines=0).
    Even a 1px grid line drops an entire row's mean significantly since
    it spans the full width (e.g. 648px wide × 1px line → mean = 0).

    Strategy:
      1. Use HIGH threshold (245) — a row is a separator if even a tiny
         fraction of pixels are dark (i.e. a thin line crosses it).
      2. If we end up with fewer bands than expected (< 20), try even
         higher thresholds to catch very faint lines.
      3. Merge bands that are too close together (< merge_gap px).
    """
    h, w = binary.shape[:2]
    row_means = np.mean(binary.astype(np.float32), axis=1)

    kernel   = np.array([0.25, 0.50, 0.25])
    smoothed = np.convolve(row_means, kernel, mode='same')

    # min_row_h: at least 1% of image height so tiny noise fragments inside a row
    # are ignored (e.g. a 61px-tall row can have a 15px dark-text valley that should
    # NOT be treated as a row separator).
    min_row_h_dyn = max(min_row_h, int(h * 0.010))   # ~25px for 2500px image
    # merge_gap: grid lines between adjacent rows are typically 1-3px in a CamScanner
    # image. 4px is enough to re-join a band split by a very thin separator without
    # accidentally merging two distinct rows.
    merge_gap = 4

    def _extract_bands(thresh):
        is_data = smoothed > thresh
        bands, in_band, start = [], False, 0
        for y in range(h):
            if is_data[y] and not in_band:
                start, in_band = y, True
            elif not is_data[y] and in_band:
                if y - start >= min_row_h_dyn:
                    bands.append([start, y])
                in_band = False
        if in_band and h - start >= min_row_h_dyn:
            bands.append([start, h])
        # Merge bands whose gap is ≤ merge_gap (a thin horizontal grid line
        # that happens to split one row into two fragments)
        merged = []
        for b in bands:
            if merged and b[0] - merged[-1][1] <= merge_gap:
                merged[-1][1] = b[1]
            else:
                merged.append(b)
        return [(b[0], b[1]) for b in merged]

    # Try progressively lower thresholds until we have enough row bands.
    best = []
    for thresh in [210, 205, 200, 195, 190, 185, 180, 175, 170, 160, 128]:
        bands = _extract_bands(thresh)
        log.info("  _find_row_bands thresh=%.0f → %d bands", thresh, len(bands))
        if len(bands) > len(best):
            best = bands
        if len(best) >= 20:          # found enough rows — stop early
            break

    log.info("  _find_row_bands: best=%d bands (merge_gap=%dpx min_h=%dpx)",
             len(best), merge_gap, min_row_h_dyn)
    return best



def _find_att_split(binary: np.ndarray) -> int:
    """
    Find the x-coordinate where the text columns (Last Name / First Name / Network)
    end and the attendance tick-box section begins.

    Strategy: the attendance section has many close vertical lines;
    the text section has only 2. We find the first x >= 28% where the
    running density of vertical-line pixels spikes.
    Returns an int x-pixel position.
    """
    h, w = binary.shape
    inv  = cv2.bitwise_not(binary)

    # Thin vertical kernel: span must reach at least 40% of row height
    span = max(int(h * 0.40), 20)
    vk   = cv2.getStructuringElement(cv2.MORPH_RECT, (1, span))
    vert = cv2.morphologyEx(inv, cv2.MORPH_OPEN, vk)
    v_proj = np.sum(vert, axis=0).astype(np.float32)

    # The JIL attendance sheet text columns (Last/First/Network) occupy
    # roughly 50-55% of page width.  Search 35-75% for the first strong
    # vertical-line cluster that marks the start of the attendance grid.
    x_lo, x_hi = int(w * 0.35), int(w * 0.75)
    region = v_proj[x_lo:x_hi]
    if region.max() > 0:
        thresh = region.max() * 0.4
        peaks  = np.where(region > thresh)[0]
        if len(peaks) > 0:
            split = x_lo + int(peaks[0])
            log.info("  att split detected at x=%d (%.0f%%)", split, 100*split/w)
            return split

    # Fallback: ~53% of width (JIL sheet layout — observed across test images)
    fallback = int(w * 0.53)
    log.info("  att split fallback x=%d (%.0f%%)", fallback, 100*fallback/w)
    return fallback

def _find_text_col_splits(binary: np.ndarray, att_x0: int) -> tuple:
    """
    Detect x-positions of the two vertical separators inside the text section:
      0 .. x_col1 = LAST NAME
      x_col1 .. x_col2 = FIRST NAME
      x_col2 .. att_x0 = NETWORK
    Uses the vertical projection of the binary image.
    """
    h, w = binary.shape[:2]
    text_bin = binary[:, :att_x0]
    inv  = cv2.bitwise_not(text_bin)
    span = max(int(h * 0.30), 15)
    vk   = cv2.getStructuringElement(cv2.MORPH_RECT, (1, span))
    vert = cv2.morphologyEx(inv, cv2.MORPH_OPEN, vk)
    v_proj = np.sum(vert, axis=0).astype(np.float32)

    if v_proj.max() > 0:
        thresh = v_proj.max() * 0.3
        peaks  = np.where(v_proj > thresh)[0]
        # Separator 1: between LAST NAME and FIRST NAME (~40-70% of att_x0)
        lo1, hi1 = int(att_x0 * 0.35), int(att_x0 * 0.68)
        left_pk  = peaks[(peaks >= lo1) & (peaks <= hi1)]
        # Separator 2: between FIRST NAME and NETWORK (~72-95% of att_x0)
        lo2, hi2 = int(att_x0 * 0.70), int(att_x0 * 0.97)
        right_pk = peaks[(peaks >= lo2) & (peaks <= hi2)]

        x1 = int(left_pk.mean())  if len(left_pk)  > 0 else int(att_x0 * 0.42)
        x2 = int(right_pk.mean()) if len(right_pk) > 0 else int(att_x0 * 0.78)
        log.debug("  text col splits: x1=%d x2=%d att_x0=%d", x1, x2, att_x0)
        return x1, x2

    # Fallback: Last Name ~42%, Network starts ~78% of the text section
    return int(att_x0 * 0.42), int(att_x0 * 0.78)


def _ocr_col(gray_strip) -> tuple:
    """OCR a single-column grayscale strip, upscaled 3x. Returns (text, conf)."""
    if gray_strip is None or gray_strip.size == 0:
        return "", 0
    h, w = gray_strip.shape[:2]
    scale = 3
    up = cv2.resize(gray_strip, (w * scale, h * scale),
                    interpolation=cv2.INTER_LANCZOS4)
    padded = cv2.copyMakeBorder(up, 8, 8, 10, 10,
                                cv2.BORDER_CONSTANT, value=255)
    if OCR_AVAILABLE:
        try:
            pil  = PILImage.fromarray(padded)
            data = pytesseract.image_to_data(
                pil,
                config="--psm 7 --oem 3 -c tessedit_char_whitelist="
                       "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz ",
                output_type=pytesseract.Output.DICT
            )
            words, confs = [], []
            for txt, conf in zip(data["text"], data["conf"]):
                if str(conf).strip() not in ("-1", "") and txt.strip():
                    words.append(txt.strip().upper())
                    confs.append(int(conf))
            if words:
                return " ".join(words), int(sum(confs) / len(confs))
        except Exception as e:
            log.debug("  _ocr_col error: %s", e)
    return "", 0


def _ocr_row_strip(padded_binary) -> tuple:
    """
    OCR a single padded binary row-strip image.
    Returns (last_name, last_conf, first_name, first_conf, network, net_conf).
    """
    words, confs = [], []

    if OCR_AVAILABLE:
        try:
            pil  = PILImage.fromarray(padded_binary)
            data = pytesseract.image_to_data(
                pil,
                config="--psm 7 --oem 3 -c tessedit_char_whitelist="
                       "ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz",
                output_type=pytesseract.Output.DICT
            )
            for txt, conf in zip(data["text"], data["conf"]):
                if str(conf).strip() not in ("-1", "") and txt.strip():
                    words.append(txt.strip().upper())
                    confs.append(int(conf))
        except Exception as e:
            log.warning("  pytesseract row error: %s", e)
    elif EASYOCR_AVAILABLE:
        try:
            results = _easy_reader.readtext(padded_binary, detail=1, paragraph=False)
            words = [r[1].upper().strip() for r in results]
            confs = [int(r[2] * 100) for r in results]
        except Exception as e:
            log.warning("  easyocr row error: %s", e)

    if not words:
        return "", 0, "", 0, "", 0

    # Identify network token (last word if it matches a known network)
    network, net_conf = "", 0
    name_words, name_confs = list(words), list(confs)
    if name_words and name_words[-1] in ALLOWED_NETWORKS | {"M", "W", "K", "Y"}:
        raw_net  = name_words.pop()
        net_conf = name_confs.pop() if name_confs else 50
        network  = _normalize_network(raw_net)
    elif len(name_words) >= 2 and " ".join(name_words[-2:]) in ALLOWED_NETWORKS:
        raw_net    = " ".join(name_words[-2:])
        name_words = name_words[:-2]
        net_conf   = int(sum(name_confs[-2:]) / 2) if len(name_confs) >= 2 else 50
        name_confs = name_confs[:-2]
        network    = _normalize_network(raw_net)

    last_name  = name_words[0] if name_words else ""
    first_name = " ".join(name_words[1:]) if len(name_words) > 1 else ""
    last_conf  = name_confs[0] if name_confs else 0
    first_conf = int(sum(name_confs[1:]) / max(1, len(name_confs) - 1)) \
                 if len(name_confs) > 1 else 0

    return last_name, last_conf, first_name, first_conf, network, net_conf

def _process_image_with_gemini(img_bytes: bytes,
                               sunday_num: int = 1,
                               service_num: int = 1) -> list:
    """
    Send the image to Gemini Vision API and parse the structured JSON response.
    Now also reads attendance for the selected sunday/service column.
    Returns a list of row dicts, or raises an exception on failure.
    """
    if not GEMINI_API_KEY:
        raise RuntimeError("GEMINI_API_KEY not set")

    # Compute which attendance column to read (1-indexed for the prompt)
    col_num = (sunday_num - 1) * 2 + service_num  # 1-10
    ordinals = {1: "1st", 2: "2nd", 3: "3rd", 4: "4th", 5: "5th"}
    col_label = f"{ordinals.get(sunday_num, str(sunday_num))} Sunday, {ordinals.get(service_num, str(service_num))} Service"

    prompt = GEMINI_PROMPT_TEMPLATE.format(col_num=col_num, col_label=col_label)
    log.info("  Gemini prompt: reading attendance column %d (%s)", col_num, col_label)

    # Encode image as base64
    b64 = base64.b64encode(img_bytes).decode("utf-8")

    # Detect mime type (JPEG vs PNG)
    mime = "image/jpeg"
    if img_bytes[:4] == b"\x89PNG":
        mime = "image/png"

    payload = {
        "contents": [{
            "parts": [
                {"text": prompt},
                {"inline_data": {"mime_type": mime, "data": b64}}
            ]
        }],
        "generationConfig": {
            "temperature": 0,
            "response_mime_type": "application/json"
        }
    }

    # ── Server-side rate limiter ──────────────────────────────────────────────
    global _gemini_last_call_ts
    with _gemini_lock:
        now     = time.time()
        elapsed = now - _gemini_last_call_ts
        if elapsed < _GEMINI_MIN_INTERVAL:
            wait = _GEMINI_MIN_INTERVAL - elapsed
            log.info("  Rate limiter: queuing Gemini call in %.1fs ...", wait)
            time.sleep(wait)
        _gemini_last_call_ts = time.time()

    GEMINI_FALLBACK_MODELS = [
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite",
        "gemini-flash-latest",
        "gemini-flash-lite-latest",
        "gemini-pro-latest",
        "gemini-2.5-pro"
    ]
    
    last_exc = None
    raw_text = None
    
    for model_name in GEMINI_FALLBACK_MODELS:
        gemini_url = f"https://generativelanguage.googleapis.com/v1beta/models/{model_name}:generateContent"
        
        # Try up to 2 times per model respecting RetryDelay from Google.
        model_failed = False
        for attempt in range(1, 3):
            try:
                resp = http_requests.post(
                    gemini_url,
                    params={"key": GEMINI_API_KEY},
                    json=payload,
                    timeout=60
                )
                if resp.status_code == 429:
                    log.warning("%s 429 payload: %s", model_name, resp.text)
                    
                    delay = 8.0 # default fallback
                    try:
                        err_data = resp.json()
                        for detail in err_data.get("error", {}).get("details", []):
                            if "retryDelay" in detail:
                                delay_str = detail["retryDelay"].replace("s", "")
                                delay = float(delay_str) + 0.5
                                break
                    except Exception:
                        pass
                    
                    # If Google tells us to wait > 20s, it's a daily/hard quota exhaustion.
                    # Switch to the next model immediately instead of waiting.
                    if delay > 20.0:
                        log.info("  %s 429 quota exhausted (delay %.1fs) -> switching to next model", model_name, delay)
                        model_failed = True
                        last_exc = Exception(f"{model_name} quota exhausted")
                        break
                        
                    delay = min(delay, 100.0)
                    last_exc = Exception(f"429 rate limit (attempt {attempt}). Pausing {delay:.1f}s.")
                    log.info("  %s 429 — back-off %.1fs (attempt %d/2)...", model_name, delay, attempt)
                    time.sleep(delay)
                    continue
                    
                resp.raise_for_status()
                # SUCCESS! Extract text and break out of the attempt loop
                raw_text = resp.json()["candidates"][0]["content"]["parts"][0]["text"]
                model_failed = False
                break
                
            except http_requests.exceptions.HTTPError as e:
                if resp.status_code in (429, 503):
                    log.warning("%s %d payload: %s", model_name, resp.status_code, resp.text)
                    if resp.status_code == 503:
                        log.info("  %s 503 unavailable -> switching to next model", model_name)
                        model_failed = True
                        last_exc = Exception(f"{model_name} unavailable")
                        break
                    last_exc = e
                    log.info("  %s HTTPError — back-off 8s (attempt %d/2)...", model_name, attempt)
                    time.sleep(8)
                    continue
                if resp.status_code == 404:
                    log.info("  %s 404 not found -> switching to next model", model_name)
                    model_failed = True
                    break
                raise
            except Exception as e:
                last_exc = e
                model_failed = True
                break
                
        if not model_failed and raw_text is not None:
            break # We got a successful response from this model!
    else:
        raise last_exc or RuntimeError("All Gemini models exhausted or rate-limited")


    # Strip markdown fences if present
    cleaned = raw_text.strip()
    if cleaned.startswith("```"):
        cleaned = "\n".join(cleaned.split("\n")[1:])
    if cleaned.endswith("```"):
        cleaned = "\n".join(cleaned.split("\n")[:-1])

    data = json.loads(cleaned)
    raw_rows = data.get("rows", [])

    # Compute the 0-indexed column position for the attendance array
    col_idx = col_num - 1  # 0-9

    rows_out = []
    for r in raw_rows:
        last_name  = str(r.get("last_name",  "")).strip().upper()
        first_name = str(r.get("first_name", "")).strip().upper()
        network    = str(r.get("network",    "")).strip().upper()

        if not last_name and not first_name:
            continue

        network  = _normalize_network(network)
        flagged  = network not in ALLOWED_NETWORKS and bool(network)

        # Build the 10-element attendance array: prioritize Gemini's full array
        raw_att = r.get("attendance")
        if isinstance(raw_att, list) and len(raw_att) == 10:
            attendance = [bool(val) for val in raw_att]
        else:
            # Fallback/compatibility: if Gemini returned single attended field
            attended = bool(r.get("attended", False))
            attendance = [False] * 10
            attendance[col_idx] = attended

        rows_out.append({
            "last_name":      last_name,  "last_name_conf":  95,
            "first_name":     first_name, "first_name_conf": 95,
            "network":        network,    "network_conf":    90,
            "flagged":        flagged,
            "attendance":     attendance,
        })

    attended_count = sum(1 for r in rows_out if r["attendance"][col_idx])
    log.info("  Gemini extracted %d rows, %d attended.", len(rows_out), attended_count)
    return rows_out


def _enhance_for_gemini(img_bytes: bytes) -> bytes:
    """
    Pre-process the raw camera photo into a clean, flat document image
    before sending to Gemini.  Steps:
      1. Auto-orient (portrait)
      2. Resize to ≤ MAX_WIDTH
      3. Perspective warp (if a reliable document quad is found)
      4. CLAHE contrast enhancement (grayscale)
      5. Unsharp-mask sharpening
      6. Re-encode as high-quality JPEG
    Returns the enhanced image as JPEG bytes.
    """
    bgr = _decode_image(img_bytes)
    if bgr is None or bgr.size == 0:
        log.warning("  _enhance_for_gemini: could not decode image — using raw bytes.")
        return img_bytes

    img_bgr = _auto_orient(bgr)
    img_bgr = _resize(img_bgr, max_width=1600)

    # 1. Mathematically Perfect Paper Cropping
    grid_corners = _find_document_corners(img_bgr)
    
    found_corners = None
    if grid_corners is not None:
        tl, tr, br, bl = grid_corners
        left_vec = bl - tl
        right_vec = br - tr
        
        tl_new = tl - left_vec * 0.20
        tr_new = tr - right_vec * 0.20
        bl_new = bl + left_vec * 0.05
        br_new = br + right_vec * 0.05
        
        top_vec_new = tr_new - tl_new
        bottom_vec_new = br_new - bl_new
        
        tl_final = tl_new - top_vec_new * 0.05
        bl_final = bl_new - bottom_vec_new * 0.05
        tr_final = tr_new + top_vec_new * 0.05
        br_final = br_new + bottom_vec_new * 0.05
        
        h, w = img_bgr.shape[:2]
        found_corners = np.array([
            np.clip(tl_final, [0, 0], [w, h]),
            np.clip(tr_final, [0, 0], [w, h]),
            np.clip(br_final, [0, 0], [w, h]),
            np.clip(bl_final, [0, 0], [w, h])
        ], dtype=np.float32)

    if found_corners is not None:
        img_bgr = _perspective_warp(img_bgr, found_corners)
        
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    
    # 2. Perfect Lighting Flattening (Background Division)
    kernel = np.ones((15, 15), np.uint8)
    bg = cv2.dilate(gray, kernel, iterations=1)
    bg = cv2.GaussianBlur(bg, (21, 21), 0)

    gray_f = gray.astype(np.float32)
    bg_f = bg.astype(np.float32)
    bg_f[bg_f == 0] = 1.0 # Avoid division by zero
    
    diff = (gray_f / bg_f) * 255.0
    diff = np.clip(diff, 0, 255).astype(np.uint8)

    # 3. Razor Sharp Text Binarization
    clean_binary = cv2.adaptiveThreshold(
        diff, 255,
        cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
        cv2.THRESH_BINARY,
        blockSize=21, C=15
    )

    _, buf = cv2.imencode(".jpg", clean_binary, [cv2.IMWRITE_JPEG_QUALITY, 95])
    log.info("  Enhanced image for Gemini: %dx%d → %d bytes",
             clean_binary.shape[1], clean_binary.shape[0], len(buf))
    return buf.tobytes()


def _process_image_to_rows(img_bytes: bytes,
                           sunday_num: int = 1,
                           service_num: int = 1) -> list:
    """
    Primary:  Gemini Vision API — reads the whole image in one shot.
    Fallback: Row-band brightness projection + Tesseract OCR per strip.
    """
    # ── Try Gemini first ──────────────────────────────────────────────────────
    if GEMINI_API_KEY:
        try:
            # Pre-process the raw camera photo into a clean flat document
            # before Gemini sees it — perspective correction + CLAHE sharpening.
            enhanced_bytes = _enhance_for_gemini(img_bytes)
            rows = _process_image_with_gemini(enhanced_bytes, sunday_num, service_num)
            if rows:
                return rows
            log.warning("  Gemini returned 0 rows — falling back to CV pipeline.")
        except Exception:
            log.warning("  Gemini failed:\n%s\n  Falling back to CV pipeline.",
                        traceback.format_exc())

    # ── CV + Tesseract fallback ───────────────────────────────────────────────
    log.info("  Using bounding-box Tesseract pipeline.")

    # IMPORTANT: Always use the RAW image for Tesseract, never the enhanced
    # image from _enhance_for_gemini(). The enhanced image has perspective-
    # corrected geometry with completely different column positions, which
    # breaks all column-boundary math for Tesseract.
    bgr = _decode_image(img_bytes)
    if bgr is None or bgr.size == 0:
        log.warning("  Could not decode image.")
        return []

    bgr  = _auto_orient(bgr)
    bgr  = _resize(bgr)
    h, w = bgr.shape[:2]
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    clahe    = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    enhanced = clahe.apply(gray)
    filt     = cv2.bilateralFilter(enhanced, 9, 75, 75)
    binary   = cv2.adaptiveThreshold(filt, 255,
                                     cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                     cv2.THRESH_BINARY, 21, 8)
    log.info("  Image %dx%d", w, h)

    # Step 1: find where attendance columns start
    att_x0 = _find_att_split(binary)
    log.info("  att_x0=%d (%.0f%% of width)", att_x0, 100 * att_x0 / w)

    if not OCR_AVAILABLE:
        log.warning("  Tesseract not available — returning empty list.")
        return []

    # Step 2: OCR the text section (left of attendance grid) at 2x scale
    ts_binary = binary[:, :att_x0]
    
    # ERASURE OF VERTICAL LINES:
    # Erase vertical grid lines by detecting tall lines and overwriting them with white (255)
    inv_ts = cv2.bitwise_not(ts_binary)
    vert_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (1, 40))
    v_lines = cv2.morphologyEx(inv_ts, cv2.MORPH_OPEN, vert_kernel)
    ts_clean = cv2.bitwise_or(ts_binary, v_lines)
    
    scale = 2
    ts_up = cv2.resize(ts_clean,
                       (ts_clean.shape[1] * scale, ts_clean.shape[0] * scale),
                       interpolation=cv2.INTER_LANCZOS4)

    HEADER_WORDS = {"LAST", "LASTNAME", "NAME", "CHURCH", "MONTH", "YEAR",
                    "JESUS", "JIL", "LORD", "ANTIPOLO", "FIRST", "NETWORK",
                    "1ST", "2ND", "3RD", "4TH", "5TH"}

    try:
        pil_img = PILImage.fromarray(ts_up)
        data = pytesseract.image_to_data(
            pil_img,
            config="--psm 11 --oem 3 -c tessedit_char_whitelist="
                   "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz ",
            output_type=pytesseract.Output.DICT
        )
    except Exception as e:
        log.warning("  Tesseract full-page OCR failed: %s", e)
        return []

    # Step 3: collect valid tokens with original-scale coordinates
    tokens = []
    for i, txt in enumerate(data["text"]):
        if not txt.strip():
            continue
        conf = data["conf"][i]
        if str(conf).strip() in ("-1", ""):
            continue
        tokens.append({
            "text": txt.strip().upper(),
            "conf": int(conf),
            "x":    data["left"][i]   / scale,
            "y":    data["top"][i]    / scale,
            "h":    data["height"][i] / scale,
        })

    if not tokens:
        log.warning("  No tokens extracted.")
        return []

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

    # Step 5: estimate column boundaries from token X distribution
    all_x = [t["x"] for t in tokens]

    def _gap_boundary(all_x, lo_frac, hi_frac, fallback_frac, att_w):
        lo, hi   = att_w * lo_frac, att_w * hi_frac
        region_x = [x for x in all_x if lo <= x <= hi]
        if not region_x:
            return int(att_w * fallback_frac)
        hist, edges = np.histogram(region_x, bins=20)
        min_bin = int(np.argmin(hist))
        return int((edges[min_bin] + edges[min_bin + 1]) / 2)

    # First Name column usually starts between 15% and 35%
    x_col1 = _gap_boundary(all_x, 0.15, 0.35, 0.28, att_x0)
    # Network column usually starts between 45% and 70%
    x_col2 = _gap_boundary(all_x, 0.45, 0.70, 0.56, att_x0)
    log.info("  Col splits: x_col1=%d x_col2=%d att_x0=%d", x_col1, x_col2, att_x0)

    # Step 6: assign tokens to columns, build row dicts
    rows_out = []
    for grp in row_groups:
        grp.sort(key=lambda t: t["x"])
        last_toks  = [t for t in grp if t["x"] < x_col1]
        first_toks = [t for t in grp if x_col1 <= t["x"] < x_col2]
        net_toks   = [t for t in grp if t["x"] >= x_col2]

        last_name   = " ".join(t["text"] for t in last_toks).strip()
        first_name  = " ".join(t["text"] for t in first_toks).strip()
        network_raw = " ".join(t["text"] for t in net_toks).strip()

        last_conf  = int(np.mean([t["conf"] for t in last_toks]))  if last_toks  else 0
        first_conf = int(np.mean([t["conf"] for t in first_toks])) if first_toks else 0
        net_conf   = int(np.mean([t["conf"] for t in net_toks]))   if net_toks   else 0

        if not last_name and not first_name:
            continue
        if last_name in HEADER_WORDS or first_name in HEADER_WORDS:
            continue
        combined = (last_name + " " + first_name).upper()
        if any(hw in combined for hw in ("LAST NAME", "FIRST NAME", "NETWORK")):
            continue

        # Smart post-processing: extract Network if it got merged into First Name
        network_suffixes = [
            ("CHILDREN", "CHILDREN"),
            ("CHILIREN", "CHILDREN"),
            ("CHILUIREN", "CHILDREN"),
            ("CHILOREN", "CHILDREN"),
            ("WOMEN", "WOMEN"),
            ("WOMIN", "WOMEN"),
            ("WOMN", "WOMEN"),
            ("IWOMEN", "WOMEN"),
            ("MEN", "MEN"),
            ("IMEN", "MEN"),
            ("KKB", "KKB"),
            ("YAN", "YAN")
        ]
        
        # Check first_name for network suffixes
        for suffix, actual_net in network_suffixes:
            if first_name.endswith(" " + suffix) or first_name.endswith(suffix):
                if not network_raw or len(network_raw) <= 2 or network_raw in ("NET", "I", "L", "A", "E", "EE", "AA", "SS"):
                    network_raw = actual_net
                    if first_name.endswith(" " + suffix):
                        first_name = first_name[:-(len(suffix)+1)].strip()
                    else:
                        first_name = first_name[:-len(suffix)].strip()
                break
                
        # Also check last_name just in case First Name was empty
        for suffix, actual_net in network_suffixes:
            if last_name.endswith(" " + suffix) or last_name.endswith(suffix):
                if not network_raw or len(network_raw) <= 2 or network_raw in ("NET", "I", "L", "A", "E", "EE", "AA", "SS"):
                    network_raw = actual_net
                    if last_name.endswith(" " + suffix):
                        last_name = last_name[:-(len(suffix)+1)].strip()
                    else:
                        last_name = last_name[:-len(suffix)].strip()
                break

        network = _normalize_network(network_raw)
        if network not in ALLOWED_NETWORKS:
            best_match, best_score = "", 0
            for known in ALLOWED_NETWORKS:
                overlap = sum(c in network_raw.upper() for c in known)
                score   = overlap / max(len(known), 1)
                if score > best_score:
                    best_score, best_match = score, known
            if best_score >= 0.5:
                network  = best_match
                net_conf = int(best_score * 100)

        flagged = (
            last_conf  < MIN_CONFIDENCE or
            first_conf < MIN_CONFIDENCE or
            bool(network and network not in ALLOWED_NETWORKS)
        )
        rows_out.append({
            "last_name":      last_name,  "last_name_conf":  int(last_conf),
            "first_name":     first_name, "first_name_conf": int(first_conf),
            "network":        network,    "network_conf":    int(net_conf),
            "flagged":        flagged,
            "attendance":     [False] * 10,
        })

    log.info("  Bounding-box pipeline: extracted %d rows.", len(rows_out))
    return rows_out

# =============================================================================
# FLASK ROUTES
# =============================================================================

@app.route("/debug", methods=["POST"])
def debug_pipeline():
    """
    POST /debug — multipart/form-data: image (file)
    Returns JSON with base64-encoded intermediate images so you can see
    exactly what each processing step produces:
      {
        "original_b64": "...",   # resized RGB input
        "binary_b64":   "...",   # after adaptive threshold
        "horiz_b64":    "...",   # horizontal line mask
        "vert_b64":     "...",   # vertical line mask
        "h_lines": [...],        # detected horizontal y-positions
        "v_lines": [...],        # detected vertical x-positions
        "rows_found": 12
      }
    """
    import base64

    image_file = request.files.get("image")
    if not image_file:
        return jsonify({"error": "No image provided."}), 400

    try:
        img_bytes = image_file.read()
        bgr = _decode_image(img_bytes)
        if bgr is None or bgr.size == 0:
            return jsonify({"error": "Could not decode image."}), 400

        bgr = _auto_orient(bgr)
        bgr = _resize(bgr)

        binary, gray = _camscanner_preprocess(
            _decode_image(img_bytes)
        )

        horiz, vert  = _detect_grid_lines(binary)
        h_lines      = _find_sorted_lines(horiz, axis=1)
        v_lines      = _find_sorted_lines(vert,  axis=0)

        def to_b64(img):
            _, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 70])
            return base64.b64encode(buf).decode()

        return jsonify({
            "original_b64": to_b64(bgr),
            "binary_b64":   to_b64(binary),
            "horiz_b64":    to_b64(horiz),
            "vert_b64":     to_b64(vert),
            "h_lines":      h_lines,
            "v_lines":      v_lines,
            "rows_found":   max(0, len(h_lines) - 1),
            "cols_found":   max(0, len(v_lines) - 1),
        })
    except Exception:
        log.error("Debug error:\n%s", traceback.format_exc())
        return jsonify({"error": "Processing failed.", "trace": traceback.format_exc()}), 500


@app.route("/enhance", methods=["POST"])
def enhance():
    """
    POST /enhance
    Accepts a single image (multipart/form-data, field: 'image').
    Runs the full CamScanner-style preprocessing pipeline and returns
    the enhanced image as a high-quality JPEG.

    Response: image/jpeg bytes (200) or JSON error (400/500).
    """
    image_file = request.files.get("image")
    if not image_file:
        return jsonify({"error": "No image provided."}), 400

    log.info("POST /enhance — file=%s", image_file.filename)

    try:
        img_bytes = image_file.read()
        bgr = _decode_image(img_bytes)
        if bgr is None or bgr.size == 0:
            return jsonify({"error": "Could not decode image."}), 400

        img_bgr = _auto_orient(bgr)
        img_bgr = _resize(img_bgr) # Resizes to max width 1600 for performance

        gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
        
        # 1. Mathematically Perfect Paper Cropping
        # We detect the internal grid perfectly, then expand its corners 
        # generously (20% up, 5% sides) to guarantee we capture the entire 
        # paper and headers without grabbing the messy desk.
        grid_corners = _find_document_corners(img_bgr)
        
        found_corners = None
        if grid_corners is not None:
            tl, tr, br, bl = grid_corners
            
            left_vec = bl - tl
            right_vec = br - tr
            
            # Expand Top (20%) to definitely include the header, Bottom (5%)
            tl_new = tl - left_vec * 0.20
            tr_new = tr - right_vec * 0.20
            bl_new = bl + left_vec * 0.05
            br_new = br + right_vec * 0.05
            
            top_vec_new = tr_new - tl_new
            bottom_vec_new = br_new - bl_new
            
            # Expand Left (5%) and Right (5%)
            tl_final = tl_new - top_vec_new * 0.05
            bl_final = bl_new - bottom_vec_new * 0.05
            
            tr_final = tr_new + top_vec_new * 0.05
            br_final = br_new + bottom_vec_new * 0.05
            
            h, w = img_bgr.shape[:2]
            found_corners = np.array([
                np.clip(tl_final, [0, 0], [w, h]),
                np.clip(tr_final, [0, 0], [w, h]),
                np.clip(br_final, [0, 0], [w, h]),
                np.clip(bl_final, [0, 0], [w, h])
            ], dtype=np.float32)

        # Apply crop
        if found_corners is not None:
            img_bgr = _perspective_warp(img_bgr, found_corners)
            
        gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
        
        # 2. Perfect Lighting Flattening (Background Division)
        # We divide the image by its own blurred background. This instantly
        # flattens all shadows and lighting, making the paper pure white
        # without blowing out faint text.
        kernel = np.ones((15, 15), np.uint8)
        bg = cv2.dilate(gray, kernel, iterations=1)
        bg = cv2.GaussianBlur(bg, (21, 21), 0)

        gray_f = gray.astype(np.float32)
        bg_f = bg.astype(np.float32)
        bg_f[bg_f == 0] = 1.0 # Avoid division by zero
        
        diff = (gray_f / bg_f) * 255.0
        diff = np.clip(diff, 0, 255).astype(np.uint8)

        # 3. Razor Sharp Text Binarization
        # We use a small blockSize (21) from the proven OCR logic, paired with 
        # C=15 on the flattened lighting image. This guarantees razor sharp, 
        # un-smudged text that preserves faint headers flawlessly.
        clean_binary = cv2.adaptiveThreshold(
            diff, 255,
            cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
            cv2.THRESH_BINARY,
            blockSize=21, C=15
        )

        _, buf = cv2.imencode(".jpg", clean_binary, [cv2.IMWRITE_JPEG_QUALITY, 95])
        enhanced_bytes = buf.tobytes()

        log.info("  Enhanced image: %dx%d → %d bytes",
                 clean_binary.shape[1], clean_binary.shape[0], len(enhanced_bytes))

        return send_file(
            io.BytesIO(enhanced_bytes),
            mimetype="image/jpeg",
            as_attachment=False,
            download_name="enhanced.jpg"
        )

    except Exception:
        log.error("Enhance error:\n%s", traceback.format_exc())
        return jsonify({"error": "Enhancement failed."}), 500


@app.route("/health", methods=["GET"])
def health():
    """Health check endpoint for production monitoring."""
    return jsonify({
        "status": "ok",
        "ocr_engine": "tesseract" if OCR_AVAILABLE else ("easyocr" if EASYOCR_AVAILABLE else "none"),
        "gemini_configured": bool(GEMINI_API_KEY),
    })


@app.route("/scan", methods=["POST"])
def scan():
    """
    POST /scan
    Processes ONE image and returns structured JSON for the Review screen.
    """
    image_file = request.files.get("image")
    if not image_file:
        return jsonify({"error": "No image provided."}), 400

    try:
        sunday_num  = int(request.form.get("sunday_num",  "1"))
        service_num = int(request.form.get("service_num", "1"))
    except ValueError:
        return jsonify({"error": "sunday_num and service_num must be integers."}), 400

    log.info("POST /scan — file=%s sunday=%d service=%d",
             image_file.filename, sunday_num, service_num)

    try:
        rows = _process_image_to_rows(image_file.read(), sunday_num, service_num)
    except Exception:
        log.error("Scan error:\n%s", traceback.format_exc())
        return jsonify({"error": "Processing failed."}), 500

    flagged_count = sum(1 for r in rows if r["flagged"])

    return jsonify({
        "rows":          rows,
        "flagged_count": flagged_count,
        "total_rows":    len(rows),
        "sunday_num":    sunday_num,
        "service_num":   service_num,
    })


@app.route("/export", methods=["POST"])
def export_csv():
    """
    POST /export
    Accepts reviewed JSON, returns a CSV matching the sample format:

        First Name,Last Name,Check In Kind
        LETECIA,ACEBEDO,Regular @ 7:30am

    Only rows whose attendance[col_idx] == True are included,
    where col_idx = (sunday_num-1)*2 + (service_num-1).

    Filename: "1ST SERVICE_APR_05 - Check-Ins Report.csv"
    """
    data = request.get_json(force=True, silent=True)
    if not data:
        return jsonify({"error": "JSON body required."}), 400

    rows        = data.get("rows", [])
    sunday_num  = int(data.get("sunday_num",  1))
    service_num = int(data.get("service_num", 1))
    month_name  = data.get("month",  datetime.now().strftime("%B").upper())
    year        = int(data.get("year", datetime.now().year))

    if not rows:
        return jsonify({"error": "No rows provided."}), 400

    # Attendance column index for the selected sunday/service
    col_idx = (sunday_num - 1) * 2 + (service_num - 1)

    # Check-in kind label matching sample output
    CHECK_IN_KIND = {
        1: "Regular @ 7:00am",
        2: "Regular @ 9:00am",
    }
    check_in_kind = CHECK_IN_KIND.get(service_num, "Regular @ 7:00am")

    # Build records — only include people who attended this session
    records = []
    for r in rows:
        att = r.get("attendance", [False] * 10)
        if col_idx < len(att) and att[col_idx]:
            records.append({
                "First Name":    r.get("first_name", "").strip().upper(),
                "Last Name":     r.get("last_name",  "").strip().upper(),
                "Check In Kind": check_in_kind,
            })

    # Sort by Last Name then First Name (matches sample ordering)
    records.sort(key=lambda x: (x["Last Name"], x["First Name"]))

    df = pd.DataFrame(records, columns=["First Name", "Last Name", "Check In Kind"])
    csv_str   = df.to_csv(index=False)
    csv_bytes = io.BytesIO(csv_str.encode("utf-8"))

    # Compute the actual calendar date of the Nth Sunday in the month
    # so the filename matches e.g. "1ST SERVICE_APR_05 - Check-Ins Report.csv"
    import calendar as _cal
    try:
        month_idx  = list(_cal.month_name).index(month_name.capitalize())
        first_day  = datetime(year, month_idx, 1)
        # weekday(): Mon=0 … Sun=6; find offset to first Sunday
        first_sunday_offset = (6 - first_day.weekday()) % 7
        sunday_day = first_day.day + first_sunday_offset + (sunday_num - 1) * 7
        date_str   = f"{sunday_day:02d}"
        month_abbr = first_day.strftime("%b").upper()
    except Exception:
        date_str   = "??"
        month_abbr = datetime.now().strftime("%b").upper()

    ordinals      = {1: "1ST", 2: "2ND", 3: "3RD", 4: "4TH", 5: "5TH"}
    service_label = f"{ordinals.get(service_num, str(service_num))} SERVICE"
    fname         = f"{service_label}_{month_abbr}_{date_str} - Check-Ins Report.csv"

    log.info("POST /export — %d attendees → %s", len(records), fname)

    return send_file(
        csv_bytes,
        mimetype="text/csv",
        as_attachment=True,
        download_name=fname
    )


# ── Legacy /upload endpoint (backwards compat) ────────────────────────────────
@app.route("/upload", methods=["POST"])
def upload():
    """Legacy endpoint — processes images and returns a simple 3-col CSV."""
    try:
        sunday_num  = int(request.form.get("sunday_num",  "1"))
        service_num = int(request.form.get("service_num", "0"))
        if not (1 <= sunday_num <= 5) or service_num not in (1, 2):
            raise ValueError
    except ValueError:
        return jsonify({"error": "sunday_num 1-5 and service_num 1-2 required."}), 400

    image_files = request.files.getlist("images")
    if not image_files:
        return jsonify({"error": "No images uploaded."}), 400

    col_idx       = 3 + (sunday_num - 1) * 2 + (service_num - 1)
    check_in_kind = SERVICE_KIND.get(service_num, "Unknown Service")

    all_records = []
    for img_file in image_files:
        try:
            rows = _process_image_to_rows(img_file.read(), sunday_num, service_num)
            for ri, row in enumerate(rows):
                att = row.get("attendance", [])
                if col_idx - 3 < len(att) and att[col_idx - 3]:
                    all_records.append({
                        "Last Name":     row["last_name"],
                        "First Name":    row["first_name"],
                        "Check-In Kind": check_in_kind,
                    })
        except Exception:
            log.error(traceback.format_exc())

    df = pd.DataFrame(all_records) if all_records else pd.DataFrame(
        columns=["Last Name", "First Name", "Check-In Kind"])
    df.sort_values(by="Last Name", inplace=True)

    csv_bytes = io.BytesIO(df.to_csv(index=False).encode("utf-8"))
    service_label = "1st Service" if service_num == 1 else "2nd Service"
    return send_file(csv_bytes, mimetype="text/csv", as_attachment=True,
                     download_name=f"Week {sunday_num} - {service_label}.csv")


# =============================================================================
# Entry point
# =============================================================================

if __name__ == "__main__":
    log.info("OCR engine: pytesseract=%s, easyocr=%s", OCR_AVAILABLE, EASYOCR_AVAILABLE)
    app.run(host="0.0.0.0", port=5000, debug=False)
