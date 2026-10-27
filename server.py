"""
Flask Backend & OCR Test Server for Medical Staff Schedule Tracker.

Provides:
- POST /api/ocr/test: Upload endpoint returning OCR.space extraction.
- GET  /api/health: Healthcheck and configuration status (never leaks API key).
- GET  /: Test interface for uploading, previewing, and validating roster OCR.
"""

import os
from pathlib import Path
from flask import Flask, request, jsonify, render_template_string

from ocr_space_client import (
    OCRSpaceClient,
    OCRSpaceError,
    OCRSpaceConfigError,
    OCRSpaceValidationError,
    OCRSpaceAPIError,
)

app = Flask(__name__)
# Maximum upload payload allowed by Flask: 10MB (client still enforces 1MB OCR.space limit)
app.config["MAX_CONTENT_LENGTH"] = 10 * 1024 * 1024


HTML_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Medical Staff Roster OCR Tester — OCR.space</title>
  <style>
    :root {
      --bg: #0f172a;
      --card-bg: #1e293b;
      --card-border: #334155;
      --text: #f8fafc;
      --text-muted: #94a3b8;
      --primary: #38bdf8;
      --primary-hover: #0284c7;
      --accent: #818cf8;
      --success: #34d399;
      --danger: #f87171;
      --warning: #fbbf24;
      --code-bg: #090d16;
    }

    * {
      box-sizing: border-box;
      margin: 0;
      padding: 0;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    }

    body {
      background: var(--bg);
      color: var(--text);
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      padding: 2rem 1rem;
    }

    .container {
      max-width: 960px;
      margin: 0 auto;
      width: 100%;
    }

    header {
      margin-bottom: 2rem;
      text-align: center;
    }

    header h1 {
      font-size: 2rem;
      font-weight: 700;
      background: linear-gradient(135deg, #38bdf8, #818cf8);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
      margin-bottom: 0.5rem;
    }

    header p {
      color: var(--text-muted);
      font-size: 0.95rem;
    }

    .card {
      background: var(--card-bg);
      border: 1px solid var(--card-border);
      border-radius: 12px;
      padding: 1.5rem;
      margin-bottom: 1.5rem;
      box-shadow: 0 4px 20px rgba(0, 0, 0, 0.25);
    }

    .dropzone {
      border: 2px dashed var(--card-border);
      border-radius: 8px;
      padding: 2.5rem 1.5rem;
      text-align: center;
      cursor: pointer;
      transition: all 0.2s ease;
      background: rgba(15, 23, 42, 0.4);
    }

    .dropzone:hover, .dropzone.dragover {
      border-color: var(--primary);
      background: rgba(56, 189, 248, 0.05);
    }

    .dropzone svg {
      width: 48px;
      height: 48px;
      fill: var(--text-muted);
      margin-bottom: 1rem;
    }

    .dropzone p {
      margin-bottom: 0.5rem;
      font-weight: 500;
    }

    .dropzone .subtext {
      color: var(--text-muted);
      font-size: 0.85rem;
    }

    input[type="file"] {
      display: none;
    }

    .selected-file-info {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-top: 1rem;
      padding: 0.75rem 1rem;
      background: rgba(56, 189, 248, 0.1);
      border: 1px solid rgba(56, 189, 248, 0.3);
      border-radius: 6px;
      font-size: 0.9rem;
    }

    .options-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 1rem;
      margin: 1.25rem 0;
      padding: 1rem;
      background: rgba(15, 23, 42, 0.4);
      border-radius: 8px;
    }

    .option-item {
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }

    .option-item label {
      font-size: 0.8rem;
      font-weight: 600;
      color: var(--text-muted);
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }

    .option-item select, .option-item input[type="text"] {
      background: var(--bg);
      border: 1px solid var(--card-border);
      color: var(--text);
      padding: 0.5rem 0.75rem;
      border-radius: 6px;
      font-size: 0.9rem;
    }

    .toggle-group {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-top: 0.3rem;
    }

    .btn {
      background: var(--primary);
      color: #0f172a;
      border: none;
      padding: 0.75rem 1.5rem;
      font-size: 0.95rem;
      font-weight: 600;
      border-radius: 8px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.5rem;
      transition: background 0.2s ease, transform 0.1s ease;
      width: 100%;
    }

    .btn:hover:not(:disabled) {
      background: var(--primary-hover);
      transform: translateY(-1px);
    }

    .btn:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .btn-secondary {
      background: #334155;
      color: var(--text);
      width: auto;
      padding: 0.4rem 0.8rem;
      font-size: 0.85rem;
    }

    .btn-secondary:hover:not(:disabled) {
      background: #475569;
    }

    .alert {
      padding: 1rem 1.25rem;
      border-radius: 8px;
      margin-bottom: 1.5rem;
      display: none;
      font-size: 0.9rem;
      line-height: 1.4;
    }

    .alert-error {
      background: rgba(248, 113, 113, 0.15);
      border: 1px solid var(--danger);
      color: #fca5a5;
    }

    .alert-info {
      background: rgba(56, 189, 248, 0.15);
      border: 1px solid var(--primary);
      color: #7dd3fc;
    }

    .spinner {
      border: 3px solid rgba(255, 255, 255, 0.2);
      border-top-color: #ffffff;
      border-radius: 50%;
      width: 18px;
      height: 18px;
      animation: spin 0.8s linear infinite;
      display: inline-block;
    }

    @keyframes spin {
      to { transform: rotate(360deg); }
    }

    .meta-chips {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      margin-bottom: 1rem;
    }

    .chip {
      background: #334155;
      color: var(--text);
      padding: 0.3rem 0.75rem;
      border-radius: 20px;
      font-size: 0.8rem;
      font-weight: 500;
    }

    .chip-success {
      background: rgba(52, 211, 153, 0.2);
      color: var(--success);
      border: 1px solid rgba(52, 211, 153, 0.4);
    }

    .preview-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 1rem;
    }

    .preview-box {
      background: var(--code-bg);
      border: 1px solid var(--card-border);
      border-radius: 8px;
      padding: 1.25rem;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 0.88rem;
      color: #e2e8f0;
      white-space: pre-wrap;
      word-break: break-word;
      max-height: 500px;
      overflow-y: auto;
      line-height: 1.6;
    }

    .pages-tabs {
      display: flex;
      gap: 0.5rem;
      margin-bottom: 0.75rem;
      overflow-x: auto;
    }

    .tab-btn {
      background: #1e293b;
      border: 1px solid var(--card-border);
      color: var(--text-muted);
      padding: 0.4rem 0.8rem;
      border-radius: 6px;
      cursor: pointer;
      font-size: 0.85rem;
    }

    .tab-btn.active {
      background: var(--primary);
      color: #0f172a;
      border-color: var(--primary);
      font-weight: 600;
    }

    footer {
      margin-top: auto;
      text-align: center;
      color: var(--text-muted);
      font-size: 0.8rem;
      padding-top: 2rem;
    }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <h1>Medical Staff Schedule Tracker</h1>
      <p>OCR.space Cloud API Testing & Text Extraction Workbench</p>
    </header>

    <div id="errorAlert" class="alert alert-error"></div>

    <div class="card">
      <div id="dropzone" class="dropzone">
        <svg viewBox="0 0 24 24">
          <path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z"/>
        </svg>
        <p>Click or drag & drop roster image or PDF here</p>
        <p class="subtext">Supports JPG, JPEG, PNG, or PDF &bull; Max 1 MB &bull; PDFs max 3 pages (Free Tier)</p>
        <input type="file" id="fileInput" accept=".jpg,.jpeg,.png,.pdf,image/jpeg,image/png,application/pdf">
      </div>

      <div id="selectedFileInfo" class="selected-file-info" style="display: none;">
        <span id="fileName">No file selected</span>
        <button type="button" class="btn-secondary" id="clearFileBtn">Change</button>
      </div>

      <div class="options-grid">
        <div class="option-item">
          <label for="ocrEngine">OCR Engine</label>
          <select id="ocrEngine">
            <option value="2" selected>Engine 2 (Best for tables/numbers)</option>
            <option value="1">Engine 1 (Standard engine)</option>
          </select>
        </div>
        <div class="option-item">
          <label for="isTable">Table Parsing Mode</label>
          <select id="isTable">
            <option value="true" selected>Enabled (isTable=true)</option>
            <option value="false">Disabled (plain text)</option>
          </select>
        </div>
        <div class="option-item">
          <label for="scale">Scale Enhancement</label>
          <select id="scale">
            <option value="true" selected>True (low-res boost)</option>
            <option value="false">False</option>
          </select>
        </div>
        <div class="option-item">
          <label for="language">Language</label>
          <select id="language">
            <option value="eng" selected>English (eng)</option>
          </select>
        </div>
      </div>

      <button id="extractBtn" class="btn" disabled>
        <span id="btnText">Extract Roster Text</span>
      </button>
    </div>

    <div id="resultCard" class="card" style="display: none;">
      <div class="preview-header">
        <h2>Extracted Text Preview</h2>
        <button id="copyBtn" class="btn-secondary">Copy Text</button>
      </div>

      <div id="metaChips" class="meta-chips"></div>

      <div id="pagesTabs" class="pages-tabs" style="display: none;"></div>

      <div id="previewBox" class="preview-box"></div>
    </div>

    <footer>
      Medical Staff Schedule Tracker &bull; Local & Cloud OCR Verification &bull; API Key Protected Server-Side
    </footer>
  </div>

  <script>
    const dropzone = document.getElementById('dropzone');
    const fileInput = document.getElementById('fileInput');
    const selectedFileInfo = document.getElementById('selectedFileInfo');
    const fileNameSpan = document.getElementById('fileName');
    const clearFileBtn = document.getElementById('clearFileBtn');
    const extractBtn = document.getElementById('extractBtn');
    const btnText = document.getElementById('btnText');
    const errorAlert = document.getElementById('errorAlert');
    const resultCard = document.getElementById('resultCard');
    const previewBox = document.getElementById('previewBox');
    const metaChips = document.getElementById('metaChips');
    const pagesTabs = document.getElementById('pagesTabs');
    const copyBtn = document.getElementById('copyBtn');

    let currentFile = null;
    let currentResponse = null;

    dropzone.addEventListener('click', () => fileInput.click());
    dropzone.addEventListener('dragover', (e) => {
      e.preventDefault();
      dropzone.classList.add('dragover');
    });
    dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragover'));
    dropzone.addEventListener('drop', (e) => {
      e.preventDefault();
      dropzone.classList.remove('dragover');
      if (e.dataTransfer.files.length > 0) {
        handleFileSelect(e.dataTransfer.files[0]);
      }
    });

    fileInput.addEventListener('change', () => {
      if (fileInput.files.length > 0) {
        handleFileSelect(fileInput.files[0]);
      }
    });

    clearFileBtn.addEventListener('click', () => {
      currentFile = null;
      fileInput.value = '';
      dropzone.style.display = 'block';
      selectedFileInfo.style.display = 'none';
      extractBtn.disabled = true;
      hideError();
    });

    function showError(msg) {
      errorAlert.innerHTML = `<strong>Error:</strong> ${escapeHtml(msg)}`;
      errorAlert.style.display = 'block';
      errorAlert.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }

    function hideError() {
      errorAlert.style.display = 'none';
      errorAlert.textContent = '';
    }

    function escapeHtml(str) {
      return String(str).replace(/[&<>"']/g, (m) => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
      }[m]));
    }

    function handleFileSelect(file) {
      hideError();
      const ext = '.' + file.name.split('.').pop().toLowerCase();
      const allowed = ['.jpg', '.jpeg', '.png', '.pdf'];
      if (!allowed.includes(ext)) {
        showError(`Unsupported file format '${ext}'. Allowed: JPG, PNG, PDF.`);
        return;
      }

      const sizeMB = (file.size / (1024 * 1024)).toFixed(2);
      if (file.size > 1024 * 1024) {
        showError(`File size (${sizeMB} MB) exceeds OCR.space free tier limit of 1 MB (1024 KB). Please compress or crop.`);
        return;
      }

      currentFile = file;
      dropzone.style.display = 'none';
      fileNameSpan.textContent = `Selected: ${file.name} (${sizeMB} MB)`;
      selectedFileInfo.style.display = 'flex';
      extractBtn.disabled = false;
    }

    extractBtn.addEventListener('click', async () => {
      if (!currentFile) return;

      hideError();
      extractBtn.disabled = true;
      btnText.innerHTML = `<span class="spinner"></span> Processing document with OCR.space...`;

      const formData = new FormData();
      formData.append('file', currentFile);
      formData.append('ocr_engine', document.getElementById('ocrEngine').value);
      formData.append('is_table', document.getElementById('isTable').value);
      formData.append('scale', document.getElementById('scale').value);
      formData.append('language', document.getElementById('language').value);

      try {
        const res = await fetch('/api/ocr/test', {
          method: 'POST',
          body: formData,
        });

        const data = await res.json();
        if (!res.ok || !data.success) {
          throw new Error(data.error || `HTTP ${res.status}: Failed to extract text`);
        }

        currentResponse = data;
        renderResults(data);
      } catch (err) {
        showError(err.message || 'Unknown network error occurred');
      } finally {
        extractBtn.disabled = false;
        btnText.textContent = 'Extract Roster Text';
      }
    });

    function renderResults(data) {
      resultCard.style.display = 'block';

      // Render meta chips
      metaChips.innerHTML = `
        <span class="chip chip-success">&#10003; Status: Ready</span>
        <span class="chip">Provider: ${escapeHtml(data.provider)}</span>
        <span class="chip">Pages: ${data.metadata.page_count}</span>
        <span class="chip">Duration: ${data.metadata.processing_time_ms} ms</span>
        <span class="chip">Engine: ${data.metadata.ocr_engine}</span>
        <span class="chip">Table Mode: ${data.metadata.is_table}</span>
      `;

      // Render page tabs if multi-page
      if (data.pages && data.pages.length > 1) {
        pagesTabs.style.display = 'flex';
        pagesTabs.innerHTML = `
          <button class="tab-btn active" onclick="showTab(-1)">Combined All Pages</button>
          ${data.pages.map((p, i) => `<button class="tab-btn" onclick="showTab(${i})">Page ${p.page_number}</button>`).join('')}
        `;
      } else {
        pagesTabs.style.display = 'none';
        pagesTabs.innerHTML = '';
      }

      previewBox.textContent = data.text;
      resultCard.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }

    window.showTab = function(idx) {
      const tabs = pagesTabs.querySelectorAll('.tab-btn');
      tabs.forEach((t, i) => {
        t.classList.toggle('active', (idx === -1 && i === 0) || (idx === i - 1));
      });

      if (idx === -1) {
        previewBox.textContent = currentResponse.text;
      } else {
        previewBox.textContent = currentResponse.pages[idx].text || '(No text on this page)';
      }
    };

    copyBtn.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(previewBox.textContent);
        const originalText = copyBtn.textContent;
        copyBtn.textContent = 'Copied!';
        setTimeout(() => { copyBtn.textContent = originalText; }, 2000);
      } catch (e) {
        alert('Failed to copy to clipboard');
      }
    });
  </script>
</body>
</html>
"""


@app.route("/", methods=["GET"])
def index():
    """Serves the test UI for roster OCR testing."""
    return render_template_string(HTML_PAGE)


@app.route("/api/health", methods=["GET"])
def health():
    """Health check endpoint indicating whether OCR_API_KEY is configured."""
    has_key = bool(os.getenv("OCR_API_KEY") and os.getenv("OCR_API_KEY").strip())
    return jsonify({
        "status": "ok",
        "service": "medical-roster-ocr",
        "provider": "ocr.space",
        "has_api_key": has_key,
    })


@app.route("/api/ocr/test", methods=["POST"])
def ocr_test():
    """
    Dedicated endpoint for testing OCR.space extraction from uploaded documents.
    Accepts multipart/form-data with 'file'.
    """
    if "file" not in request.files:
        return jsonify({
            "success": False,
            "error": "No file uploaded. Request must include a 'file' multipart field.",
            "error_type": "ValidationError",
        }), 400

    uploaded_file = request.files["file"]
    if not uploaded_file or not uploaded_file.filename:
        return jsonify({
            "success": False,
            "error": "Empty filename provided.",
            "error_type": "ValidationError",
        }), 400

    # Read parameters
    ocr_engine = int(request.form.get("ocr_engine", 2))
    is_table = request.form.get("is_table", "true").lower() in ("true", "1", "yes")
    scale = request.form.get("scale", "true").lower() in ("true", "1", "yes")
    language = request.form.get("language", "eng")

    try:
        client = OCRSpaceClient()
    except OCRSpaceConfigError as exc:
        return jsonify({
            "success": False,
            "error": str(exc),
            "error_type": "ConfigError",
        }), 500

    try:
        file_bytes = uploaded_file.read()
        result = client.parse_file(
            file_bytes=file_bytes,
            filename=uploaded_file.filename,
            ocr_engine=ocr_engine,
            is_table=is_table,
            scale=scale,
            language=language,
        )
        return jsonify(result), 200

    except OCRSpaceValidationError as exc:
        return jsonify({
            "success": False,
            "error": str(exc),
            "error_type": "ValidationError",
        }), 400

    except OCRSpaceAPIError as exc:
        return jsonify({
            "success": False,
            "error": str(exc),
            "error_type": "APIError",
        }), 502

    except Exception as exc:
        return jsonify({
            "success": False,
            "error": f"Internal server error: {exc}",
            "error_type": "ServerError",
        }), 500


if __name__ == "__main__":
    port = int(os.getenv("PORT", 5001))
    print(f"Medical Staff Schedule Tracker OCR Server starting on http://127.0.0.1:{port}")
    app.run(host="127.0.0.1", port=port, debug=False)
