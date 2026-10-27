#!/usr/bin/env python3
"""
Manual live integration test for OCR.space API.
Uses the real API key from .env to test actual cloud extraction.
"""

import os
import sys
from pathlib import Path

# Ensure root directory is in sys.path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from PIL import Image, ImageDraw

from ocr_space_client import (
    OCRSpaceClient,
    OCRSpaceError,
)


def create_sample_roster_image(path: Path) -> Path:
    """Generates a small synthetic roster image (~15 KB) to test live extraction."""
    img = Image.new("RGB", (400, 150), color="white")
    draw = ImageDraw.Draw(img)
    # Draw simple roster table header and row
    draw.text((20, 20), "ROSTER: SEPTEMBER 2026", fill="black")
    draw.text((20, 50), "ID       D1  D2  D3  D4  D5", fill="black")
    draw.text((20, 80), "ABHL341   M   E   N   OFF  M", fill="black")
    img.save(path, format="JPEG", quality=85)
    return path


def main():
    print("=== OCR.space Live Integration Test ===")
    api_key = os.getenv("OCR_API_KEY")
    if not api_key:
        from dotenv import load_dotenv
        load_dotenv()
        api_key = os.getenv("OCR_API_KEY")

    if not api_key:
        print("ERROR: OCR_API_KEY not found in environment or .env file.")
        sys.exit(1)

    masked_key = api_key[:4] + "..." + api_key[-4:] if len(api_key) > 8 else "***"
    print(f"Loaded API key: {masked_key} (masked for security)")

    # Target test image
    if len(sys.argv) > 1 and Path(sys.argv[1]).is_file():
        test_file = Path(sys.argv[1])
        print(f"Using provided file: {test_file} ({test_file.stat().st_size / 1024:.1f} KB)")
    else:
        sample_path = Path("/tmp/sample_test_roster.jpg")
        create_sample_roster_image(sample_path)
        test_file = sample_path
        print(f"Generated synthetic test roster: {test_file} ({test_file.stat().st_size / 1024:.1f} KB)")

    client = OCRSpaceClient(api_key=api_key)

    try:
        print("Sending request to OCR.space API (Engine=2, isTable=true)...")
        res = client.parse_path(str(test_file), ocr_engine=2, is_table=True)

        print("\n--- LIVE EXTRACTION SUCCESSFUL ---")
        print(f"Provider:        {res['provider']}")
        print(f"Processing Time: {res['metadata']['processing_time_ms']} ms")
        print(f"Page Count:      {res['metadata']['page_count']}")
        print("\nExtracted Text:\n" + "=" * 40)
        print(res["text"])
        print("=" * 40)

        # Cleanup temporary sample image
        if test_file.name == "sample_test_roster.jpg" and test_file.exists():
            test_file.unlink()

        print("\nLive OCR.space API integration verified successfully.")

    except OCRSpaceError as exc:
        print(f"\nExtraction failed: {exc}")
        sys.exit(2)


if __name__ == "__main__":
    main()
