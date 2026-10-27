"""
OCR.space API Client for Medical Staff Schedule Tracker.

Handles document validation (1 MB size limit, 3-page PDF limit),
secure multipart request dispatching, and response parsing.
"""

import io
import os
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import requests
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

try:
    import pypdf
except ImportError:
    pypdf = None


class OCRSpaceError(Exception):
    """Base exception for OCR.space errors."""
    pass


class OCRSpaceConfigError(OCRSpaceError):
    """Raised when configuration such as OCR_API_KEY is missing or invalid."""
    pass


class OCRSpaceValidationError(OCRSpaceError):
    """Raised when an input document fails validation (size, page count, format)."""
    pass


class OCRSpaceAPIError(OCRSpaceError):
    """Raised when the OCR.space API returns an error or unexpected response."""
    pass


class OCRSpaceClient:
    """Client for interacting with the OCR.space free OCR API."""

    ENDPOINT = "https://api.ocr.space/parse/image"
    MAX_FILE_SIZE_BYTES = 1024 * 1024  # 1 MB free tier limit
    MAX_PDF_PAGES = 3  # 3-page free tier limit
    SUPPORTED_EXTENSIONS = {".jpg", ".jpeg", ".png", ".pdf"}
    DEFAULT_TIMEOUT_SECONDS = 30

    MIME_TYPES = {
        ".jpg": "image/jpeg",
        ".jpeg": "image/jpeg",
        ".png": "image/png",
        ".pdf": "application/pdf",
    }

    def __init__(
        self,
        api_key: Optional[str] = None,
        endpoint: str = ENDPOINT,
        timeout: int = DEFAULT_TIMEOUT_SECONDS,
    ):
        raw_key = api_key or os.getenv("OCR_API_KEY")
        if not raw_key or not str(raw_key).strip():
            raise OCRSpaceConfigError(
                "OCR_API_KEY is not configured. Please set OCR_API_KEY in your environment or .env file."
            )
        self.api_key = str(raw_key).strip()
        self.endpoint = endpoint
        self.timeout = timeout

    @classmethod
    def get_file_type(cls, filename: str) -> str:
        ext = Path(filename).suffix.lower()
        if ext not in cls.SUPPORTED_EXTENSIONS:
            supported = ", ".join(sorted(cls.SUPPORTED_EXTENSIONS))
            raise OCRSpaceValidationError(
                f"Unsupported file type '{ext}'. Supported formats are: {supported}."
            )
        return ext

    @classmethod
    def count_pdf_pages(cls, file_bytes: bytes) -> int:
        """Counts pages in a PDF using pypdf with fallback to byte scanning."""
        if pypdf is not None:
            try:
                reader = pypdf.PdfReader(io.BytesIO(file_bytes))
                return len(reader.pages)
            except Exception as exc:
                raise OCRSpaceValidationError(f"Could not parse PDF document structure: {exc}")

        # Fallback heuristic if pypdf is unavailable
        import re
        matches = re.findall(rb"/Type\s*/Page\b", file_bytes)
        return len(matches) if matches else 1

    def validate_document(self, file_bytes: bytes, filename: str) -> Tuple[str, int]:
        """
        Validates file size, extension, and PDF page count.
        Returns (extension, page_count).
        """
        if not file_bytes:
            raise OCRSpaceValidationError("The uploaded file is empty (0 bytes).")

        size = len(file_bytes)
        if size > self.MAX_FILE_SIZE_BYTES:
            size_mb = size / (1024 * 1024)
            raise OCRSpaceValidationError(
                f"File size ({size_mb:.2f} MB) exceeds OCR.space free tier limit of 1 MB (1024 KB). "
                "Please compress or crop the file before uploading."
            )

        ext = self.get_file_type(filename)
        page_count = 1

        if ext == ".pdf":
            page_count = self.count_pdf_pages(file_bytes)
            if page_count > self.MAX_PDF_PAGES:
                raise OCRSpaceValidationError(
                    f"PDF has {page_count} pages, which exceeds the OCR.space free tier limit of {self.MAX_PDF_PAGES} pages. "
                    "Please split your PDF into 1 to 3 page documents before processing."
                )

        return ext, page_count

    def parse_file(
        self,
        file_bytes: bytes,
        filename: str,
        ocr_engine: int = 2,
        is_table: bool = True,
        scale: bool = True,
        language: str = "eng",
        detect_orientation: bool = True,
    ) -> Dict[str, Any]:
        """
        Submits file bytes to OCR.space and parses the returned text.
        """
        ext, page_count = self.validate_document(file_bytes, filename)
        mime_type = self.MIME_TYPES.get(ext, "application/octet-stream")

        files = {
            "file": (filename, file_bytes, mime_type)
        }
        data = {
            "apikey": self.api_key,
            "language": language,
            "isOverlayRequired": "true",
            "OCREngine": str(ocr_engine),
            "isTable": str(is_table).lower(),
            "scale": str(scale).lower(),
            "detectOrientation": str(detect_orientation).lower(),
        }
        headers = {
            "apikey": self.api_key
        }

        start_time = time.time()
        try:
            response = requests.post(
                self.endpoint,
                files=files,
                data=data,
                headers=headers,
                timeout=self.timeout,
            )
        except requests.exceptions.Timeout:
            raise OCRSpaceAPIError(
                f"OCR.space request timed out after {self.timeout} seconds. Please try again."
            )
        except requests.exceptions.RequestException as exc:
            # Mask API key if it somehow appears in exception message
            sanitized_msg = str(exc).replace(self.api_key, "[REDACTED_API_KEY]")
            raise OCRSpaceAPIError(f"Network error communicating with OCR.space: {sanitized_msg}")

        elapsed_ms = int((time.time() - start_time) * 1000)

        if response.status_code != 200:
            raise OCRSpaceAPIError(
                f"OCR.space returned HTTP error {response.status_code}: {response.text[:200]}"
            )

        try:
            payload = response.json()
        except ValueError:
            raise OCRSpaceAPIError(
                "OCR.space returned invalid or malformed JSON."
            )

        # Check for provider-level errors
        if payload.get("IsErroredOnProcessing", False):
            raw_error = payload.get("ErrorMessage")
            if isinstance(raw_error, list):
                error_msg = "; ".join(str(e) for e in raw_error)
            else:
                error_msg = str(raw_error or "Unknown OCR.space processing error")
            raise OCRSpaceAPIError(f"OCR.space error: {error_msg}")

        parsed_results = payload.get("ParsedResults") or []
        if not parsed_results:
            raise OCRSpaceAPIError("OCR.space returned no parsed results for the document.")

        pages_output: List[Dict[str, Any]] = []
        combined_texts: List[str] = []
        all_words: List[Dict[str, Any]] = []

        for idx, page in enumerate(parsed_results, start=1):
            page_text = page.get("ParsedText", "").strip()
            exit_code = page.get("FileParseExitCode")
            page_error = page.get("ErrorMessage") or page.get("ErrorDetails")

            if page_error and str(page_error).strip():
                # Some exit codes indicate failure on a page
                if exit_code not in (1, None):
                    raise OCRSpaceAPIError(f"OCR.space error on page {idx}: {page_error}")

            # Extract 2D word bounding boxes from TextOverlay
            overlay = page.get("TextOverlay") or {}
            page_words = []
            for line in overlay.get("Lines", []):
                for w in line.get("Words", []):
                    w_text = str(w.get("WordText", "")).strip()
                    if not w_text:
                        continue
                    left = float(w.get("Left", 0))
                    top = float(w.get("Top", 0))
                    width = float(w.get("Width", 0))
                    height = float(w.get("Height", 0))
                    word_obj = {
                        "text": w_text,
                        "x": left,
                        "y": top,
                        "width": width,
                        "height": height,
                        "cx": left + width / 2.0,
                        "cy": top + height / 2.0,
                        "page": idx,
                    }
                    page_words.append(word_obj)
                    all_words.append(word_obj)

            pages_output.append({
                "page_number": idx,
                "text": page_text,
                "exit_code": exit_code,
                "words": page_words,
            })
            if page_text:
                combined_texts.append(page_text)

        full_text = "\n\n".join(combined_texts).strip()

        if not full_text:
            raise OCRSpaceAPIError(
                "OCR completed successfully but no text could be recognized in the document."
            )

        api_processing_time = payload.get("ProcessingTimeInMilliseconds")
        if api_processing_time is not None:
            try:
                proc_time_ms = int(float(api_processing_time))
            except ValueError:
                proc_time_ms = elapsed_ms
        else:
            proc_time_ms = elapsed_ms

        return {
            "success": True,
            "provider": "ocr.space",
            "text": full_text,
            "pages": pages_output,
            "words": all_words,
            "metadata": {
                "filename": filename,
                "file_type": ext.lstrip("."),
                "page_count": len(pages_output),
                "processing_time_ms": proc_time_ms,
                "ocr_engine": ocr_engine,
                "is_table": is_table,
            },
        }

    def parse_path(
        self,
        file_path: str,
        ocr_engine: int = 2,
        is_table: bool = True,
        scale: bool = True,
        language: str = "eng",
    ) -> Dict[str, Any]:
        """Convenience method to parse a file directly from a local path."""
        path = Path(file_path).expanduser().resolve()
        if not path.is_file():
            raise OCRSpaceValidationError(f"File not found: {file_path}")
        file_bytes = path.read_bytes()
        return self.parse_file(
            file_bytes=file_bytes,
            filename=path.name,
            ocr_engine=ocr_engine,
            is_table=is_table,
            scale=scale,
            language=language,
        )
