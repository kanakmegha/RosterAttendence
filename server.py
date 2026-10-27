"""
Flask Backend & OCR Table Reconstruction Server for Medical Staff Schedule Tracker.

Provides:
- POST /api/ocr/test: Upload endpoint returning OCR extraction + reconstructed 2D table schedule.
- GET  /api/health: Healthcheck and configuration status (never leaks API key).
- GET  /: Web interface for uploading, previewing, and validating roster table reconstruction.
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
from table_reconstructor import RosterTableReconstructor

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 10 * 1024 * 1024


HTML_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Medical Staff Roster Table Reconstruction — OCR.space</title>
  <style>
    :root {
      --bg: #0b1120;
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
      max-width: 1020px;
      margin: 0 auto;
      width: 100%;
    }

    header {
      margin-bottom: 2rem;
      text-align: center;
    }

    header h1 {
      font-size: 2.1rem;
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
      border: 1px solid var(--card-border);
      border-radius: 6px;
      cursor: pointer;
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

    .alert-warning {
      background: rgba(251, 191, 36, 0.15);
      border: 1px solid var(--warning);
      color: #fde68a;
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
      margin-bottom: 1.25rem;
    }

    .chip {
      background: #334155;
      color: var(--text);
      padding: 0.35rem 0.75rem;
      border-radius: 20px;
      font-size: 0.8rem;
      font-weight: 500;
    }

    .chip-success {
      background: rgba(52, 211, 153, 0.2);
      color: var(--success);
      border: 1px solid rgba(52, 211, 153, 0.4);
    }

    .chip-warning {
      background: rgba(251, 191, 36, 0.2);
      color: var(--warning);
      border: 1px solid rgba(251, 191, 36, 0.4);
    }

    .emp-card {
      background: rgba(15, 23, 42, 0.6);
      border: 1px solid var(--card-border);
      border-radius: 8px;
      padding: 1rem 1.25rem;
      margin-bottom: 1.25rem;
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 1rem;
    }

    .emp-item {
      display: flex;
      flex-direction: column;
      gap: 0.2rem;
    }

    .emp-item span.label {
      font-size: 0.75rem;
      color: var(--text-muted);
      text-transform: uppercase;
      font-weight: 600;
    }

    .emp-item span.val {
      font-size: 1rem;
      font-weight: 600;
      color: var(--text);
    }

    .view-tabs {
      display: flex;
      gap: 0.5rem;
      margin-bottom: 1rem;
      border-bottom: 1px solid var(--card-border);
      padding-bottom: 0.5rem;
    }

    .view-tab-btn {
      background: transparent;
      border: none;
      color: var(--text-muted);
      font-size: 0.95rem;
      font-weight: 600;
      padding: 0.5rem 1rem;
      cursor: pointer;
      border-radius: 6px;
    }

    .view-tab-btn.active {
      background: rgba(56, 189, 248, 0.15);
      color: var(--primary);
    }

    .schedule-table-wrap {
      overflow-x: auto;
      border: 1px solid var(--card-border);
      border-radius: 8px;
    }

    table.schedule-table {
      width: 100%;
      border-collapse: collapse;
      text-align: left;
      font-size: 0.88rem;
    }

    table.schedule-table th {
      background: #0f172a;
      color: var(--text-muted);
      padding: 0.75rem 1rem;
      font-weight: 600;
      text-transform: uppercase;
      font-size: 0.75rem;
      letter-spacing: 0.05em;
      border-bottom: 1px solid var(--card-border);
    }

    table.schedule-table td {
      padding: 0.65rem 1rem;
      border-bottom: 1px solid rgba(51, 65, 85, 0.5);
    }

    table.schedule-table tr:hover {
      background: rgba(56, 189, 248, 0.03);
    }

    .badge {
      display: inline-block;
      padding: 0.25rem 0.6rem;
      border-radius: 6px;
      font-size: 0.78rem;
      font-weight: 600;
    }

    .badge-morning { background: rgba(56, 189, 248, 0.2); color: #7dd3fc; border: 1px solid rgba(56, 189, 248, 0.3); }
    .badge-evening { background: rgba(251, 191, 36, 0.2); color: #fde68a; border: 1px solid rgba(251, 191, 36, 0.3); }
    .badge-night   { background: rgba(129, 140, 248, 0.2); color: #c7d2fe; border: 1px solid rgba(129, 140, 248, 0.3); }
    .badge-off     { background: rgba(52, 211, 153, 0.2); color: #a7f3d0; border: 1px solid rgba(52, 211, 153, 0.3); }
    .badge-absent  { background: rgba(248, 113, 113, 0.2); color: #fca5a5; border: 1px solid rgba(248, 113, 113, 0.3); }
    .badge-missing { background: rgba(148, 163, 184, 0.1); color: #64748b; }

    .status-pill {
      font-size: 0.75rem;
      padding: 0.15rem 0.5rem;
      border-radius: 12px;
      display: inline-block;
    }

    .status-confirmed { background: rgba(52, 211, 153, 0.2); color: var(--success); }
    .status-review    { background: rgba(251, 191, 36, 0.2); color: var(--warning); }
    .status-missing   { background: rgba(148, 163, 184, 0.15); color: var(--text-muted); }

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
      <p>Roster Table Reconstruction & Calendar Schedule Extractor</p>
    </header>

    <div id="errorAlert" class="alert alert-error"></div>
    <div id="warningAlert" class="alert alert-warning"></div>

    <div class="card">
      <div id="dropzone" class="dropzone">
        <svg viewBox="0 0 24 24">
          <path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z"/>
        </svg>
        <p>Click or drag & drop roster image or PDF here</p>
        <p class="subtext">Supports JPG, PNG, PDF &bull; Max 1 MB &bull; PDFs max 3 pages</p>
        <input type="file" id="fileInput" accept=".jpg,.jpeg,.png,.pdf,image/jpeg,image/png,application/pdf">
      </div>

      <div id="selectedFileInfo" class="selected-file-info" style="display: none;">
        <span id="fileName">No file selected</span>
        <button type="button" class="btn-secondary" id="clearFileBtn">Change</button>
      </div>

      <div class="options-grid">
        <div class="option-item">
          <label for="staffId">Staff ID</label>
          <input type="text" id="staffId" placeholder="e.g. ABHL341" value="ABHL341">
        </div>
        <div class="option-item">
          <label for="rosterMonth">Roster Month (YYYY-MM)</label>
          <input type="text" id="rosterMonth" placeholder="e.g. 2026-09" value="2026-09">
        </div>
        <div class="option-item">
          <label for="ocrEngine">OCR Engine</label>
          <select id="ocrEngine">
            <option value="2" selected>Engine 2 (Tables/Numbers)</option>
            <option value="1">Engine 1 (Standard)</option>
          </select>
        </div>
        <div class="option-item">
          <label for="isTable">Table Mode</label>
          <select id="isTable">
            <option value="true" selected>Enabled (isTable=true)</option>
            <option value="false">Disabled</option>
          </select>
        </div>
      </div>

      <button id="extractBtn" class="btn" disabled>
        <span id="btnText">Reconstruct & Extract Schedule</span>
      </button>
    </div>

    <div id="resultCard" class="card" style="display: none;">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem;">
        <h2 id="resultTitle">Reconstructed Schedule</h2>
        <button id="copyJsonBtn" class="btn-secondary">Copy JSON</button>
      </div>

      <div id="metaChips" class="meta-chips"></div>

      <div id="empCard" class="emp-card" style="display: none;"></div>

      <div class="view-tabs">
        <button id="tabScheduleBtn" class="view-tab-btn active" onclick="switchView('schedule')">Calendar Duties</button>
        <button id="tabRawBtn" class="view-tab-btn" onclick="switchView('raw')">Raw OCR Text</button>
      </div>

      <div id="scheduleView" class="schedule-table-wrap">
        <table class="schedule-table">
          <thead>
            <tr>
              <th>Date</th>
              <th>Weekday</th>
              <th>Shift</th>
              <th>Floor / Dept</th>
              <th>Status</th>
              <th>Source Provenance</th>
            </tr>
          </thead>
          <tbody id="scheduleBody"></tbody>
        </table>
      </div>

      <div id="rawView" style="display: none;">
        <div id="previewBox" class="preview-box"></div>
      </div>
    </div>

    <footer>
      Medical Staff Schedule Tracker &bull; 2D Geometry Table Reconstruction &bull; Privacy-Safe Local Execution
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
    const warningAlert = document.getElementById('warningAlert');
    const resultCard = document.getElementById('resultCard');
    const empCard = document.getElementById('empCard');
    const metaChips = document.getElementById('metaChips');
    const scheduleBody = document.getElementById('scheduleBody');
    const previewBox = document.getElementById('previewBox');
    const copyJsonBtn = document.getElementById('copyJsonBtn');

    let currentFile = null;
    let currentData = null;

    dropzone.addEventListener('click', () => fileInput.click());
    dropzone.addEventListener('dragover', (e) => { e.preventDefault(); dropzone.classList.add('dragover'); });
    dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragover'));
    dropzone.addEventListener('drop', (e) => {
      e.preventDefault();
      dropzone.classList.remove('dragover');
      if (e.dataTransfer.files.length > 0) handleFileSelect(e.dataTransfer.files[0]);
    });

    fileInput.addEventListener('change', () => {
      if (fileInput.files.length > 0) handleFileSelect(fileInput.files[0]);
    });

    clearFileBtn.addEventListener('click', () => {
      currentFile = null;
      fileInput.value = '';
      dropzone.style.display = 'block';
      selectedFileInfo.style.display = 'none';
      extractBtn.disabled = true;
      hideAlerts();
    });

    function showError(msg) {
      errorAlert.innerHTML = `<strong>Error:</strong> ${escapeHtml(msg)}`;
      errorAlert.style.display = 'block';
      errorAlert.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }

    function showWarning(msg) {
      warningAlert.innerHTML = `<strong>Attention Required:</strong> ${escapeHtml(msg)}`;
      warningAlert.style.display = 'block';
    }

    function hideAlerts() {
      errorAlert.style.display = 'none';
      warningAlert.style.display = 'none';
    }

    function escapeHtml(str) {
      return String(str || '').replace(/[&<>"']/g, (m) => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
      }[m]));
    }

    function handleFileSelect(file) {
      hideAlerts();
      const ext = '.' + file.name.split('.').pop().toLowerCase();
      const allowed = ['.jpg', '.jpeg', '.png', '.pdf'];
      if (!allowed.includes(ext)) {
        showError(`Unsupported file format '${ext}'. Allowed: JPG, PNG, PDF.`);
        return;
      }

      const sizeMB = (file.size / (1024 * 1024)).toFixed(2);
      if (file.size > 1024 * 1024) {
        showError(`File size (${sizeMB} MB) exceeds OCR.space free tier limit of 1 MB. Please compress or crop.`);
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

      hideAlerts();
      extractBtn.disabled = true;
      btnText.innerHTML = `<span class="spinner"></span> Reconstructing table & extracting duties...`;

      const formData = new FormData();
      formData.append('file', currentFile);
      formData.append('staff_id', document.getElementById('staffId').value);
      formData.append('month', document.getElementById('rosterMonth').value);
      formData.append('ocr_engine', document.getElementById('ocrEngine').value);
      formData.append('is_table', document.getElementById('isTable').value);

      try {
        const res = await fetch('/api/ocr/test', {
          method: 'POST',
          body: formData,
        });

        const data = await res.json();
        if (!res.ok || !data.success) {
          throw new Error(data.error || `HTTP ${res.status}: Failed to extract text`);
        }

        currentData = data;
        renderResults(data);
      } catch (err) {
        showError(err.message || 'Unknown network error occurred');
      } finally {
        extractBtn.disabled = false;
        btnText.textContent = 'Reconstruct & Extract Schedule';
      }
    });

    function renderResults(data) {
      resultCard.style.display = 'block';
      const recon = data.reconstruction || {};

      if (recon.warnings && recon.warnings.length > 0) {
        showWarning(recon.warnings.join('<br>'));
      }

      // Meta chips
      const statusClass = recon.status === 'confirmed' ? 'chip-success' : 'chip-warning';
      metaChips.innerHTML = `
        <span class="chip ${statusClass}">Status: ${recon.status || 'Extracted'}</span>
        <span class="chip">Provider: ${escapeHtml(data.provider)}</span>
        <span class="chip">Month: ${recon.roster_month || 'N/A'}</span>
        <span class="chip">Tokens: ${data.metadata?.words_count || 0}</span>
        <span class="chip">Duration: ${data.metadata?.processing_time_ms || 0} ms</span>
      `;

      // Employee summary
      if (recon.staff_name || recon.matched_staff_id) {
        empCard.style.display = 'grid';
        empCard.innerHTML = `
          <div class="emp-item"><span class="label">Staff Name</span><span class="val">${escapeHtml(recon.staff_name || 'N/A')}</span></div>
          <div class="emp-item"><span class="label">Matched ID</span><span class="val">${escapeHtml(recon.matched_staff_id || 'N/A')}</span></div>
          <div class="emp-item"><span class="label">Floor / Department</span><span class="val">${escapeHtml(recon.floor_department || 'N/A')}</span></div>
          <div class="emp-item"><span class="label">Weekly Off Day</span><span class="val">${escapeHtml(recon.weekly_off_day || 'N/A')}</span></div>
        `;
      } else {
        empCard.style.display = 'none';
      }

      // Render table
      const entries = recon.entries || [];
      scheduleBody.innerHTML = entries.map(e => {
        let badgeClass = 'badge-missing';
        if (e.normalized_shift === 'Morning') badgeClass = 'badge-morning';
        else if (e.normalized_shift === 'Evening') badgeClass = 'badge-evening';
        else if (e.normalized_shift === 'Night') badgeClass = 'badge-night';
        else if (e.normalized_shift === 'Off') badgeClass = 'badge-off';
        else if (e.normalized_shift === 'Absent/Duty') badgeClass = 'badge-absent';

        const shiftDisplay = e.normalized_shift ? `<span class="badge ${badgeClass}">${e.normalized_shift} (${escapeHtml(e.raw_value)})</span>` : '<span class="badge badge-missing">Missing / Unobserved</span>';
        const statusClass = e.status === 'confirmed' ? 'status-confirmed' : (e.status === 'needs_review' ? 'status-review' : 'status-missing');

        return `
          <tr>
            <td><strong>${e.date}</strong></td>
            <td>${e.weekday}</td>
            <td>${shiftDisplay}</td>
            <td>${escapeHtml(e.floor || '-')}</td>
            <td><span class="status-pill ${statusClass}">${e.status}</span></td>
            <td><small style="color: var(--text-muted);">${escapeHtml(e.provenance || '-')}</small></td>
          </tr>
        `;
      }).join('');

      previewBox.textContent = data.text;
      switchView('schedule');
      resultCard.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }

    window.switchView = function(view) {
      document.getElementById('scheduleView').style.display = view === 'schedule' ? 'block' : 'none';
      document.getElementById('rawView').style.display = view === 'raw' ? 'block' : 'none';
      document.getElementById('tabScheduleBtn').classList.toggle('active', view === 'schedule');
      document.getElementById('tabRawBtn').classList.toggle('active', view === 'raw');
    };

    copyJsonBtn.addEventListener('click', async () => {
      if (!currentData) return;
      try {
        await navigator.clipboard.writeText(JSON.stringify(currentData.reconstruction, null, 2));
        const originalText = copyJsonBtn.textContent;
        copyJsonBtn.textContent = 'JSON Copied!';
        setTimeout(() => { copyJsonBtn.textContent = originalText; }, 2000);
      } catch (e) {
        alert('Failed to copy JSON');
      }
    });
  </script>
</body>
</html>
"""


@app.route("/", methods=["GET"])
def index():
    """Serves the test UI for roster table reconstruction."""
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
    Dedicated endpoint for testing OCR.space extraction and 2D table reconstruction.
    Accepts multipart/form-data with 'file', optional 'staff_id', and optional 'month'.
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
    staff_id = request.form.get("staff_id", "").strip() or None
    month = request.form.get("month", "").strip() or None
    ocr_engine = int(request.form.get("ocr_engine", 2))
    is_table = request.form.get("is_table", "true").lower() in ("true", "1", "yes")
    scale = request.form.get("scale", "true").lower() in ("true", "1", "yes")
    language = request.form.get("language", "eng")

    try:
        client = OCRSpaceClient(timeout=45)
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

        # Execute 2D Table Reconstruction
        reconstructor = RosterTableReconstructor(
            words=result.get("words", []),
            month_str=month,
            requested_staff_id=staff_id,
        )
        reconstruction = reconstructor.reconstruct()

        result["reconstruction"] = reconstruction
        if "metadata" in result:
            result["metadata"]["words_count"] = len(result.get("words", []))

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
