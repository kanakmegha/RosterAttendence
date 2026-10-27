
#!/usr/bin/env python3
"""Local medical roster extractor prototype. No network requests."""

import argparse
import calendar
import json
import re
import sys
import unicodedata
from pathlib import Path
from datetime import date

try:
    import fitz
except ImportError:
    fitz = None

SHIFT_MAP = {"M": "Morning", "E": "Evening", "N": "Night", "OFF": "Off"}


def normalize(value):
    value = unicodedata.normalize("NFKC", str(value)).strip().upper()
    return re.sub(r"^[^\w]+|[^\w]+$", "", value)


def normalize_id(value):
    # Preserve leading zeros.
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", str(value))).casefold()


def group_rows(words):
    if not words:
        return []

    heights = sorted(max(1.0, w["y1"] - w["y0"]) for w in words)
    tolerance = max(2.0, heights[len(heights) // 2] * 0.65)
    ordered = sorted(
        words, key=lambda w: ((w["y0"] + w["y1"]) / 2, w["x0"])
    )
    rows = []

    for word in ordered:
        cy = (word["y0"] + word["y1"]) / 2
        candidates = [
            row for row in rows[-5:]
            if abs(row["cy"] - cy) <= tolerance
        ]

        if not candidates:
            rows.append({"cy": cy, "words": [word]})
        else:
            row = min(candidates, key=lambda r: abs(r["cy"] - cy))
            row["words"].append(word)
            row["cy"] = sum(
                (w["y0"] + w["y1"]) / 2 for w in row["words"]
            ) / len(row["words"])

    for row in rows:
        row["words"].sort(key=lambda w: w["x0"])

    return sorted(rows, key=lambda r: r["cy"])


def extract_pdf(path):
    pages = []
    doc = fitz.open(path)

    for page_number, page in enumerate(doc, start=1):
        words = []
        for item in page.get_text("words", sort=True):
            x0, y0, x1, y1, text = item[:5]
            if str(text).strip():
                words.append({
                    "x0": float(x0), "y0": float(y0),
                    "x1": float(x1), "y1": float(y1),
                    "text": str(text),
                })

        pages.append({
            "page": page_number,
            "width": float(page.rect.width),
            "height": float(page.rect.height),
            "words": words,
        })

    doc.close()
    return pages


def extract_image(path):
    try:
        from PIL import Image
        import pytesseract
        from pytesseract import Output
    except ImportError:
        raise RuntimeError(
            "Install Pillow and pytesseract, plus the Tesseract application."
        )

    image = Image.open(path).convert("RGB")
    data = pytesseract.image_to_data(
        image, output_type=Output.DICT, config="--psm 6"
    )

    words = []
    for i, raw in enumerate(data["text"]):
        text = str(raw).strip()
        if not text:
            continue

        x, y = data["left"][i], data["top"][i]
        w, h = data["width"][i], data["height"][i]
        words.append({
            "x0": float(x), "y0": float(y),
            "x1": float(x + w), "y1": float(y + h),
            "text": text,
        })

    return [{
        "page": 1,
        "width": float(image.width),
        "height": float(image.height),
        "words": words,
    }]


def extract_ocr_space(path):
    """Extracts text using OCR.space cloud API and preserves real 2D bounding boxes."""
    from ocr_space_client import OCRSpaceClient
    client = OCRSpaceClient(timeout=45)
    res = client.parse_path(str(path), ocr_engine=2, is_table=True)
    words = []
    for w in res.get("words", []):
        words.append({
            "x0": float(w["x"]),
            "y0": float(w["y"]),
            "x1": float(w["x"] + w["width"]),
            "y1": float(w["y"] + w["height"]),
            "text": str(w["text"]).strip(),
        })
    max_x = max((w["x1"] for w in words), default=1200.0)
    max_y = max((w["y1"] for w in words), default=800.0)
    return [{
        "page": 1,
        "width": max(1200.0, max_x + 50.0),
        "height": max(800.0, max_y + 50.0),
        "words": words,
        "raw_text": res.get("text", ""),
        "ocr_result": res,
    }]



def extract_scanned_pdf(path):
    from PIL import Image
    import pytesseract
    from pytesseract import Output

    doc = fitz.open(path)
    pages = []

    for page_number, page in enumerate(doc, start=1):
        scale = 2.5
        pix = page.get_pixmap(
            matrix=fitz.Matrix(scale, scale), alpha=False
        )
        image = Image.frombytes("RGB", [pix.width, pix.height], pix.samples)
        data = pytesseract.image_to_data(
            image, output_type=Output.DICT, config="--psm 6"
        )

        words = []
        for i, raw in enumerate(data["text"]):
            text = str(raw).strip()
            if not text:
                continue

            x, y = data["left"][i], data["top"][i]
            w, h = data["width"][i], data["height"][i]
            words.append({
                "x0": x / scale, "y0": y / scale,
                "x1": (x + w) / scale, "y1": (y + h) / scale,
                "text": text,
            })

        pages.append({
            "page": page_number,
            "width": float(page.rect.width),
            "height": float(page.rect.height),
            "words": words,
        })

    doc.close()
    return pages


def find_id_matches(pages, staff_id):
    target = normalize_id(staff_id)
    matches = []

    for page in pages:
        rows = group_rows(page["words"])
        for index, row in enumerate(rows):
            if any(
                normalize_id(word["text"]) == target
                for word in row["words"]
            ):
                matches.append({
                    "page": page["page"],
                    "row_index": index,
                    "row": row,
                    "rows": rows,
                })

    return matches


def detect_day_header(rows):
    candidates = []

    for index, row in enumerate(rows):
        by_day = {}

        for word in row["words"]:
            token = normalize(word["text"])
            if re.fullmatch(r"\d{1,2}", token):
                day = int(token)
                if 1 <= day <= 31:
                    center = (word["x0"] + word["x1"]) / 2
                    by_day.setdefault(day, center)

        if len(by_day) >= 5:
            candidates.append((len(by_day), index, by_day))

    if not candidates:
        return None

    candidates.sort(key=lambda item: (item[0], -item[1]), reverse=True)
    _, index, positions = candidates[0]

    return {"row_index": index, "positions": positions}


def extract_shift_codes(row, day_positions, staff_id):
    positions = sorted(day_positions.items(), key=lambda item: item[1])

    spacings = [
        positions[i + 1][1] - positions[i][1]
        for i in range(len(positions) - 1)
        if positions[i + 1][1] > positions[i][1]
    ]
    spacings.sort()
    typical_spacing = spacings[len(spacings) // 2] if spacings else 10.0
    max_distance = max(typical_spacing * 0.48, 4.0)

    result = {}

    for word in row["words"]:
        raw = word["text"]
        if normalize_id(raw) == normalize_id(staff_id):
            continue

        token = normalize(raw)
        if token not in {"M", "E", "N", "OFF"}:
            continue

        center = (word["x0"] + word["x1"]) / 2
        day, day_x = min(
            day_positions.items(),
            key=lambda item: abs(item[1] - center),
        )

        if abs(day_x - center) > max_distance:
            continue

        if day in result:
            result[day] = None  # Multiple tokens: ambiguous.
        else:
            result[day] = token

    return result


def main():
    parser = argparse.ArgumentParser(
        description="Extract a staff member's roster locally."
    )
    parser.add_argument("file", help="PDF or image path")
    parser.add_argument("--id", dest="staff_id")
    parser.add_argument("--month", help="Roster month, YYYY-MM")
    parser.add_argument("--output", default="roster_extraction_report.json")
    parser.add_argument("--excel", default="monthly_schedule.xlsx")
    parser.add_argument(
        "--ocr", action="store_true",
        help="Use local OCR for scanned PDFs or images",
    )
    parser.add_argument(
        "--ocr-space", action="store_true",
        help="Use OCR.space cloud API for text extraction",
    )
    args = parser.parse_args()

    path = Path(args.file).expanduser()
    if not path.is_file():
        parser.error("Input file does not exist.")

    staff_id = args.staff_id or input(
        "Enter exact staff ID as printed in roster: "
    ).strip()
    month_text = args.month or input("Roster month (YYYY-MM): ").strip()

    if not staff_id:
        parser.error("Staff ID cannot be empty.")

    if not re.fullmatch(r"\d{4}-\d{2}", month_text):
        parser.error("Month must be YYYY-MM, e.g. 2026-10.")

    year, month = map(int, month_text.split("-"))
    if not 1 <= month <= 12:
        parser.error("Month must be between 01 and 12.")

    try:
        if args.ocr_space:
            pages = extract_ocr_space(path)
            mode = "ocr_space_cloud"
        elif path.suffix.lower() == ".pdf":
            if fitz is None:
                pages = extract_ocr_space(path)
                mode = "ocr_space_cloud"
            else:
                pages = extract_pdf(path)
                mode = "pdf_text"
                word_count = sum(len(page["words"]) for page in pages)
                if word_count < 20 and args.ocr:
                    pages = extract_scanned_pdf(path)
                    mode = "local_ocr"

        elif path.suffix.lower() in {
            ".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp"
        }:
            if args.ocr:
                try:
                    pages = extract_image(path)
                    mode = "local_ocr"
                except Exception:
                    # Fallback to OCR.space when local Tesseract is missing
                    pages = extract_ocr_space(path)
                    mode = "ocr_space_cloud"
            else:
                parser.error("Images require --ocr or --ocr-space.")

        else:
            parser.error("Supported formats: PDF, PNG, JPG, TIFF, BMP.")

    except Exception as exc:
        sys.exit(f"Could not process document: {exc}")

    if not sum(len(page["words"]) for page in pages):
        sys.exit(
            "No text extracted. Try --ocr with local Tesseract installed."
        )

    report = {
        "report_version": 1,
        "processing_mode": mode,
        "input_type": path.suffix.lower(),
        "requested_month": month_text,
        "page_count": len(pages),
        "match_count": 0,
        "staff_id_value_included": False,
        "status": "needs_review",
        "day_header_found": False,
        "detected_day_count": 0,
        "extracted_shifts": [],
        "warnings": [],
        "notes": [
            "Processed locally; no network requests were made.",
            "This prototype assumes day numbers are columns and shifts "
            "are on the same horizontal row as the staff ID.",
            "The JSON report omits the staff ID and raw row text.",
            "Extraction results must be checked against the source document.",
        ],
    }

    if mode == "ocr_space_cloud" and "ocr_result" in pages[0]:
        from table_reconstructor import RosterTableReconstructor
        reconstructor = RosterTableReconstructor(
            words=pages[0]["ocr_result"].get("words", []),
            month_str=month_text,
            requested_staff_id=staff_id,
        )
        recon = reconstructor.reconstruct()
        report["reconstruction"] = recon
        report["status"] = recon["status"]
        report["staff_name"] = recon.get("staff_name")
        report["matched_id"] = recon.get("matched_staff_id")
        report["floor"] = recon.get("floor_department")
        report["warnings"].extend(recon.get("warnings", []))
        report["match_count"] = 1 if recon.get("matched_staff_id") else 0
        report["extracted_shifts"] = [
            {
                "date": e["date"],
                "code": e["raw_value"],
                "meaning": e["normalized_shift"],
                "floor": e["floor"],
                "status": e["status"],
                "provenance": e["provenance"],
            }
            for e in recon.get("entries", [])
        ]
        if recon.get("matched_staff_id"):
            report["day_header_found"] = True
            report["detected_day_count"] = len(recon.get("entries", []))
    else:
        matches = find_id_matches(pages, staff_id)
        report["match_count"] = len(matches)

        if not matches:
            report["status"] = "id_not_found"
            report["warnings"].append(
                "Exact ID token not found. OCR may have misread or split the ID."
            )
        elif len(matches) > 1:
            report["status"] = "ambiguous_id_match"
            report["warnings"].append(
                "ID found in multiple rows/pages. Do not use results until resolved."
            )
        else:
            match = matches[0]
            print(
                "\nLOCAL PREVIEW — may contain sensitive data; "
                "do not share this terminal output:"
            )
            print(" | ".join(w["text"] for w in match["row"]["words"]))

            header = detect_day_header(match["rows"])

            if not header:
                report["status"] = "id_found_header_not_detected"
                report["warnings"].append(
                    "ID found, but day-number header was not detected "
                    "on the same page."
                )
            else:
                report["day_header_found"] = True
                positions = header["positions"]
                report["detected_day_count"] = len(positions)
                report["detected_header_days"] = sorted(positions)
                codes = extract_shift_codes(
                    match["row"], positions, staff_id
                )

                for day in range(1, calendar.monthrange(year, month)[1] + 1):
                    code = codes.get(day)
                    if code is None:
                        report["extracted_shifts"].append({
                            "date": date(year, month, day).isoformat(),
                            "code": None,
                            "meaning": None,
                            "status": "missing_or_ambiguous",
                        })
                        report["warnings"].append(
                            f"Day {day}: missing, unreadable, or ambiguous. "
                            "Not assumed to be OFF."
                        )
                    else:
                        report["extracted_shifts"].append({
                            "date": date(year, month, day).isoformat(),
                            "code": code,
                            "meaning": SHIFT_MAP[code],
                            "status": "needs_human_confirmation",
                        })

                report["status"] = "extraction_complete_needs_review"

    Path(args.output).expanduser().write_text(
        json.dumps(report, indent=2), encoding="utf-8"
    )
    print(f"\nJSON report: {Path(args.output).expanduser().resolve()}")
    print(f"Processing mode: {mode}")
    print(f"ID matches: {len(matches)}")
    print(f"Status: {report['status']}")

    if len(matches) == 1 and report["day_header_found"]:
        try:
            from openpyxl import Workbook
            from openpyxl.styles import Font, PatternFill

            wb = Workbook()
            ws = wb.active
            ws.title = "Monthly Schedule"
            ws.append([
                "Date", "Weekday", "Shift Code", "Shift Meaning", "Status"
            ])

            for item in report["extracted_shifts"]:
                d = date.fromisoformat(item["date"])
                ws.append([
                    item["date"], d.strftime("%A"),
                    item["code"] or "", item["meaning"] or "",
                    item["status"],
                ])

            for cell in ws[1]:
                cell.font = Font(bold=True)
                cell.fill = PatternFill("solid", fgColor="D9EAF7")

            ws.freeze_panes = "A2"
            ws.auto_filter.ref = ws.dimensions

            for col, width in {
                "A": 14, "B": 14, "C": 14, "D": 20, "E": 32
            }.items():
                ws.column_dimensions[col].width = width

            excel_path = Path(args.excel).expanduser()
            wb.save(excel_path)
            print(f"Excel file: {excel_path.resolve()}")
            print("WARNING: Review the unverified results before use.")

        except ImportError:
            print("Excel dependency missing. Install openpyxl.")

    else:
        print("Excel not created: unique ID and day header are both required.")

    print("Privacy: no network requests were made.")


if __name__ == "__main__":
    main()
