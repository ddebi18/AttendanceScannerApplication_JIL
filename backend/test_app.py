import sys
import numpy as np
import cv2
import app

def test_image(img_path):
    with open(img_path, "rb") as f:
        img_bytes = f.read()

    app.GEMINI_API_KEY = "FAKE"
    app.GEMINI_URL = "http://localhost:9999/fake"
    
    rows = app._process_image_to_rows(img_bytes, 1, 1)
    
    for idx, r in enumerate(rows):
        print(f"Row {idx}: LAST='{r.get('last_name')}' FIRST='{r.get('first_name')}' NET='{r.get('network')}'")

if __name__ == "__main__":
    test_image("../SampleOutput/16.jpg")
