"""
Unit tests for OCR.space integration in Medical Staff Schedule Tracker.

Mocks external network calls to test all edge cases, validations, and error handling
without consuming real OCR.space API quota.
"""

import io
import json
import pytest
from unittest.mock import patch, MagicMock
import requests

from ocr_space_client import (
    OCRSpaceClient,
    OCRSpaceConfigError,
    OCRSpaceValidationError,
    OCRSpaceAPIError,
)
from server import app


@pytest.fixture
def dummy_png_bytes():
    # Minimal 1x1 PNG bytes
    return (
        b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
        b"\x08\x06\x00\x00\x00\x1f\x15c4\x00\x00\x00\nIDATx\x9cc\x00\x01\x00\x00\x05"
        b"\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82"
    )


@pytest.fixture
def dummy_pdf_bytes():
    # Minimal valid 1-page PDF
    return (
        b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
        b"2 0 obj<</Type/Pages/Count 1/Kids[3 0 R]>>endobj\n"
        b"3 0 obj<</Type/Page/MediaBox[0 0 612 792]/Parent 2 0 R>>endobj\n"
        b"xref\n0 4\n0000000000 65535 f \n0000000009 00000 n \n0000000052 00000 n \n"
        b"0000000102 00000 n \ntrailer<</Size 4/Root 1 0 R>>\nstartxref\n178\n%%EOF\n"
    )


@pytest.fixture
def dummy_multi_page_pdf_bytes():
    # Minimal 4-page PDF to test limit (> 3 pages)
    kids = " ".join(f"{i} 0 R" for i in range(4, 8))
    page_objs = "".join(f"{i} 0 obj<</Type/Page/Parent 2 0 R>>endobj\n" for i in range(4, 8))
    return (
        f"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
        f"2 0 obj<</Type/Pages/Count 4/Kids[{kids}]>>endobj\n"
        f"3 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n"
        f"{page_objs}"
        f"xref\n0 8\n"
        f"trailer<</Size 8/Root 1 0 R>>\nstartxref\n350\n%%EOF\n"
    ).encode("ascii")


@pytest.fixture
def flask_client():
    app.config["TESTING"] = True
    with app.test_client() as client:
        yield client


# 1. Successful image OCR
def test_successful_image_ocr(dummy_png_bytes):
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {
        "ParsedResults": [
            {
                "ParsedText": "STAFF ID: ABHL341\n01 02 03\nM  E  N",
                "FileParseExitCode": 1,
            }
        ],
        "IsErroredOnProcessing": False,
        "ProcessingTimeInMilliseconds": "850",
    }

    with patch("requests.post", return_value=mock_response):
        client = OCRSpaceClient(api_key="TEST_API_KEY")
        res = client.parse_file(dummy_png_bytes, "roster.png")

        assert res["success"] is True
        assert res["provider"] == "ocr.space"
        assert "ABHL341" in res["text"]
        assert len(res["pages"]) == 1
        assert res["metadata"]["file_type"] == "png"
        assert res["metadata"]["processing_time_ms"] == 850


# 2. Successful PDF OCR
def test_successful_pdf_ocr(dummy_pdf_bytes):
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {
        "ParsedResults": [
            {
                "ParsedText": "Monthly Duty Roster - September 2026\nNurse A: M E N OFF",
                "FileParseExitCode": 1,
            }
        ],
        "IsErroredOnProcessing": False,
        "ProcessingTimeInMilliseconds": "1200",
    }

    with patch("requests.post", return_value=mock_response):
        client = OCRSpaceClient(api_key="TEST_API_KEY")
        res = client.parse_file(dummy_pdf_bytes, "roster.pdf")

        assert res["success"] is True
        assert res["metadata"]["file_type"] == "pdf"
        assert res["metadata"]["page_count"] == 1
        assert "September 2026" in res["text"]


# 3. Missing OCR_API_KEY
def test_missing_ocr_api_key():
    with patch.dict("os.environ", {}, clear=True):
        with pytest.raises(OCRSpaceConfigError) as exc_info:
            OCRSpaceClient(api_key=None)
        assert "OCR_API_KEY is not configured" in str(exc_info.value)


# 4. Invalid file type
def test_invalid_file_type(dummy_png_bytes):
    client = OCRSpaceClient(api_key="TEST_API_KEY")
    with pytest.raises(OCRSpaceValidationError) as exc_info:
        client.parse_file(dummy_png_bytes, "roster.docx")
    assert "Unsupported file type '.docx'" in str(exc_info.value)


# 5. Oversized file (> 1 MB limit on free tier)
def test_oversized_file():
    client = OCRSpaceClient(api_key="TEST_API_KEY")
    oversized_bytes = b"X" * (1024 * 1024 + 10)  # > 1MB
    with pytest.raises(OCRSpaceValidationError) as exc_info:
        client.parse_file(oversized_bytes, "huge_roster.png")
    assert "exceeds OCR.space free tier limit of 1 MB" in str(exc_info.value)


# 6. PDF exceeding supported page limit (> 3 pages)
def test_pdf_exceeding_page_limit(dummy_multi_page_pdf_bytes):
    client = OCRSpaceClient(api_key="TEST_API_KEY")
    with pytest.raises(OCRSpaceValidationError) as exc_info:
        client.parse_file(dummy_multi_page_pdf_bytes, "multi_page.pdf")
    assert "exceeds the OCR.space free tier limit of 3 pages" in str(exc_info.value)
    assert "split your PDF" in str(exc_info.value)


# 7. Invalid or corrupt provider response (not valid JSON)
def test_invalid_provider_response(dummy_png_bytes):
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.side_effect = ValueError("No JSON could be decoded")
    mock_response.text = "<html>502 Bad Gateway</html>"

    with patch("requests.post", return_value=mock_response):
        client = OCRSpaceClient(api_key="TEST_API_KEY")
        with pytest.raises(OCRSpaceAPIError) as exc_info:
            client.parse_file(dummy_png_bytes, "roster.jpg")
        assert "invalid or malformed JSON" in str(exc_info.value)


# 8. OCR.space returning an API error (IsErroredOnProcessing=True)
def test_ocr_space_api_error_payload(dummy_png_bytes):
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {
        "IsErroredOnProcessing": True,
        "ErrorMessage": ["File failed to process: E212 invalid image format"],
        "ParsedResults": None,
    }

    with patch("requests.post", return_value=mock_response):
        client = OCRSpaceClient(api_key="TEST_API_KEY")
        with pytest.raises(OCRSpaceAPIError) as exc_info:
            client.parse_file(dummy_png_bytes, "roster.jpg")
        assert "OCR.space error: File failed to process: E212 invalid image format" in str(exc_info.value)


# 9. Network timeout
def test_network_timeout(dummy_png_bytes):
    with patch("requests.post", side_effect=requests.exceptions.Timeout):
        client = OCRSpaceClient(api_key="TEST_API_KEY", timeout=5)
        with pytest.raises(OCRSpaceAPIError) as exc_info:
            client.parse_file(dummy_png_bytes, "roster.jpg")
        assert "timed out after 5 seconds" in str(exc_info.value)


# 10. Empty extracted text
def test_empty_extracted_text(dummy_png_bytes):
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {
        "IsErroredOnProcessing": False,
        "ParsedResults": [
            {
                "ParsedText": "    \n   ",
                "FileParseExitCode": 1,
            }
        ],
    }

    with patch("requests.post", return_value=mock_response):
        client = OCRSpaceClient(api_key="TEST_API_KEY")
        with pytest.raises(OCRSpaceAPIError) as exc_info:
            client.parse_file(dummy_png_bytes, "blank_image.png")
        assert "no text could be recognized" in str(exc_info.value)


# 11. Backend API Endpoint Tests (Success, Validation, and Error states)
def test_api_endpoint_success(flask_client, dummy_png_bytes):
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {
        "ParsedResults": [
            {
                "ParsedText": "STAFF: 12345 | SHIFT: MORNING",
                "FileParseExitCode": 1,
            }
        ],
        "IsErroredOnProcessing": False,
        "ProcessingTimeInMilliseconds": "900",
    }

    with patch.dict("os.environ", {"OCR_API_KEY": "MOCK_KEY"}), patch("requests.post", return_value=mock_response):
        data = {
            "file": (io.BytesIO(dummy_png_bytes), "sample.png"),
            "ocr_engine": "2",
            "is_table": "true",
        }
        res = flask_client.post("/api/ocr/test", data=data, content_type="multipart/form-data")
        assert res.status_code == 200
        payload = res.get_json()
        assert payload["success"] is True
        assert "STAFF: 12345" in payload["text"]
        assert payload["metadata"]["ocr_engine"] == 2


def test_api_endpoint_missing_file(flask_client):
    res = flask_client.post("/api/ocr/test", data={})
    assert res.status_code == 400
    payload = res.get_json()
    assert payload["success"] is False
    assert "No file uploaded" in payload["error"]


def test_api_endpoint_validation_error(flask_client):
    with patch.dict("os.environ", {"OCR_API_KEY": "MOCK_KEY"}):
        data = {
            "file": (io.BytesIO(b"dummy text"), "bad_file.txt"),
        }
        res = flask_client.post("/api/ocr/test", data=data, content_type="multipart/form-data")
        assert res.status_code == 400
        payload = res.get_json()
        assert payload["success"] is False
        assert payload["error_type"] == "ValidationError"


def test_api_health_endpoint(flask_client):
    with patch.dict("os.environ", {"OCR_API_KEY": "SECRET_KEY_123"}):
        res = flask_client.get("/api/health")
        assert res.status_code == 200
        payload = res.get_json()
        assert payload["status"] == "ok"
        assert payload["has_api_key"] is True
        # SECURITY INVARIANT: The actual API key must NEVER be in the response
        assert "SECRET_KEY_123" not in json.dumps(payload)
