"""
Flask Backend & OCR Table Reconstruction Server for Medical Staff Schedule Tracker.

Provides:
- POST /api/ocr/test: Upload endpoint returning OCR extraction + reconstructed 2D table schedule.
- GET  /api/health: Healthcheck and configuration status (never leaks API key).
- GET  /: Web interface for uploading, previewing, and validating roster table reconstruction.
"""

import os
from pathlib import Path
from flask import Flask, request, jsonify, render_template_string, send_file

from ocr_space_client import (
    OCRSpaceClient,
    OCRSpaceError,
    OCRSpaceConfigError,
    OCRSpaceValidationError,
    OCRSpaceAPIError,
)
from table_reconstructor import RosterTableReconstructor
from section_inference import infer_section_shifts, export_schedule_to_excel
from auth import require_auth, authenticate_user, generate_token, STAFF_USERS
from domain_models import (
    DraftSchedule,
    ScheduleEntry,
    ExtractedCell,
    ManualDuty,
    EntrySourceType,
    ReviewStatus,
    ExtractionDiagnostics,
)
from roster_month import RosterMonth
from revision_diff import compute_schedule_revision_diff
from db import (
    init_db,
    save_draft_schedule,
    get_draft_schedule,
    update_draft_schedule_cell,
    approve_draft_schedule,
    get_approved_schedule,
    add_manual_duty,
    get_manual_duties,
    save_job,
    get_job,
)

import uuid
from flask import g

# Initialize SQLite database
init_db()

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 10 * 1024 * 1024




HTML_PAGE = r"""<!DOCTYPE html>
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
    .badge-inferred { background: rgba(251, 191, 36, 0.25); color: #fde047; border: 1px solid #fbbf24; }
    .badge-observed { background: rgba(52, 211, 153, 0.2); color: #86efac; border: 1px solid rgba(52, 211, 153, 0.4); }

    .status-pill {
      font-size: 0.75rem;
      padding: 0.15rem 0.5rem;
      border-radius: 12px;
      display: inline-block;
    }

    .status-confirmed { background: rgba(52, 211, 153, 0.2); color: var(--success); }
    .status-review    { background: rgba(251, 191, 36, 0.2); color: var(--warning); }
    .status-inferred  { background: rgba(251, 191, 36, 0.25); color: #fde68a; border: 1px dashed var(--warning); }
    .status-missing   { background: rgba(148, 163, 184, 0.15); color: var(--text-muted); }

    .section-cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
      gap: 1rem;
      margin-bottom: 1.5rem;
    }

    .section-card {
      background: #0f172a;
      border: 1px solid var(--card-border);
      border-radius: 8px;
      padding: 1rem;
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
        <div class="option-item">
          <label for="inferSections">10-Day Block Inference</label>
          <select id="inferSections">
            <option value="true" selected>Enabled (Infer Shifts)</option>
            <option value="false">Disabled (Raw OCR Only)</option>
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
        <div>
          <button id="downloadExcelBtn" class="btn-secondary" style="margin-right: 0.5rem;">Download Excel</button>
          <button id="copyJsonBtn" class="btn-secondary">Copy JSON</button>
        </div>
      </div>

      <div id="metaChips" class="meta-chips"></div>

      <div id="sectionCards" class="section-cards" style="display: none;"></div>

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
              <th>Source Type</th>
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
      formData.append('infer_sections', document.getElementById('inferSections').value);

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

      // Section Cards
      const sectionCards = document.getElementById('sectionCards');
      const secInfo = recon.section_inference || {};
      if (secInfo.sections && secInfo.sections.length > 0) {
        sectionCards.style.display = 'grid';
        sectionCards.innerHTML = secInfo.sections.map(s => {
          const revBadge = s.review_required ? '<span class="status-pill status-review">Review</span>' : '<span class="status-pill status-confirmed">Verified</span>';
          return `
            <div class="section-card">
              <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.5rem;">
                <strong>Section ${s.section_index} (Days ${s.start_day}–${s.end_day})</strong>
                ${revBadge}
              </div>
              <p style="font-size: 0.82rem; color: var(--text-muted); margin-bottom: 0.25rem;">
                Observed Shifts: <strong>${escapeHtml(s.observed_working_shifts.join(', ') || 'None')}</strong>
              </p>
              <p style="font-size: 0.82rem; color: var(--text-muted); margin-bottom: 0.25rem;">
                Proposed Shift: <strong style="color: var(--primary);">${escapeHtml(s.proposed_shift || 'None')}</strong>
              </p>
              <p style="font-size: 0.8rem; color: var(--text-muted);">
                Inferred: ${s.inferred_dates.length} days &bull; Unresolved: ${s.unresolved_dates.length} days
              </p>
              ${s.review_reasons.length > 0 ? `<div style="font-size: 0.75rem; color: #fde68a; margin-top: 0.4rem; line-height: 1.3;">${s.review_reasons.map(r => escapeHtml(r)).join('<br>')}</div>` : ''}
            </div>
          `;
        }).join('');
      } else {
        sectionCards.style.display = 'none';
      }

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

        const shiftDisplay = e.normalized_shift ? `<span class="badge ${badgeClass}">${e.normalized_shift} (${escapeHtml(e.raw_value || '-')})</span>` : '<span class="badge badge-missing">Missing / Unobserved</span>';

        let statusClass = 'status-missing';
        if (e.status === 'confirmed' || e.status === 'observed') statusClass = 'status-confirmed';
        else if (e.status === 'needs_review') statusClass = 'status-review';
        else if (e.status === 'inferred_needs_confirmation') statusClass = 'status-inferred';

        const srcBadge = e.source_type === 'section_inference'
          ? '<span class="badge badge-inferred">Section Inferred</span>'
          : '<span class="badge badge-observed">OCR Observed</span>';

        return `
          <tr>
            <td><strong>${e.date}</strong></td>
            <td>${e.weekday}</td>
            <td>${shiftDisplay}</td>
            <td>${escapeHtml(e.floor || '-')}</td>
            <td>${srcBadge}</td>
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

    const downloadExcelBtn = document.getElementById('downloadExcelBtn');
    downloadExcelBtn.addEventListener('click', async () => {
      if (!currentData || !currentData.reconstruction) return;
      downloadExcelBtn.disabled = true;
      downloadExcelBtn.textContent = 'Generating Excel...';
      try {
        const res = await fetch('/api/export/excel', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ reconstruction: currentData.reconstruction }),
        });
        if (!res.ok) throw new Error('Excel generation failed');
        const blob = await res.blob();
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        const name = (currentData.reconstruction.staff_name || 'schedule').replace(/\s+/g, '_');
        a.download = `${name}_${currentData.reconstruction.roster_month || 'month'}.xlsx`;
        document.body.appendChild(a);
        a.click();
        a.remove();
      } catch (err) {
        alert(err.message || 'Failed to download Excel file');
      } finally {
        downloadExcelBtn.disabled = false;
        downloadExcelBtn.textContent = 'Download Excel';
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

        # Execute Section Inference stage (default: enabled)
        infer_sections = request.form.get("infer_sections", "true").lower() in ("true", "1", "yes")
        if infer_sections:
            reconstruction = infer_section_shifts(reconstruction)

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


@app.route("/api/export/excel", methods=["POST"])
def export_excel():
    """
    Exports reconstructed schedule to formatted Excel workbook.
    Accepts JSON body with 'reconstruction' dictionary.
    """
    data = request.get_json(silent=True) or {}
    reconstruction = data.get("reconstruction")
    if not reconstruction:
        return jsonify({
            "success": False,
            "error": "Missing 'reconstruction' in JSON request body",
            "error_type": "ValidationError",
        }), 400

    import tempfile
    with tempfile.NamedTemporaryFile(suffix=".xlsx", delete=False) as tmp:
        tmp_path = tmp.name

    try:
        export_schedule_to_excel(reconstruction, tmp_path)
        staff_name = (reconstruction.get("staff_name") or "staff").replace(" ", "_")
        month = reconstruction.get("roster_month") or "schedule"
        filename = f"{staff_name}_{month}_schedule.xlsx"
        return send_file(
            tmp_path,
            as_attachment=True,
            download_name=filename,
            mimetype="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )
    except Exception as exc:
        return jsonify({
            "success": False,
            "error": f"Failed to export Excel: {exc}",
            "error_type": "ExportError",
        }), 500


# =====================================================================
# REST JSON API Endpoints for Android & Mobile Client
# =====================================================================

@app.route("/api/auth/login", methods=["POST"])
def auth_login():
    """Authenticates staff member and returns bearer token."""
    data = request.get_json(silent=True) or {}
    staff_id = data.get("staff_id", "").strip()
    password = data.get("password", "").strip()

    if not staff_id or not password:
        return jsonify({"success": False, "error": "Missing staff_id or password"}), 400

    user = authenticate_user(staff_id, password)
    if not user:
        return jsonify({"success": False, "error": "Invalid staff ID or password"}), 401

    token = generate_token(user)
    return jsonify({
        "success": True,
        "token": token,
        "user": user,
    }), 200


@app.route("/api/staff/profile", methods=["GET"])
@require_auth()
def get_staff_profile():
    """Returns the profile of the authenticated staff member."""
    return jsonify({
        "success": True,
        "user": g.current_user,
    }), 200


@app.route("/api/roster/upload", methods=["POST"])
@require_auth()
def api_roster_upload():
    """
    Authenticated roster upload for Android client.
    Performs OCR and table reconstruction, storing a DraftSchedule in SQLite.
    Enforces server-side authorization: staff can only upload for themselves.
    """
    if "roster_file" not in request.files:
        return jsonify({"success": False, "error": "No file uploaded in 'roster_file'"}), 400

    uploaded_file = request.files["roster_file"]
    if not uploaded_file or not uploaded_file.filename:
        return jsonify({"success": False, "error": "Empty filename"}), 400

    # Strict server-side authorization check: regular staff cannot upload for others
    requested_id = request.form.get("staff_id", "").strip()
    if g.current_user["role"] != "supervisor":
        if requested_id and requested_id != g.current_user["staff_id"]:
            return jsonify({
                "success": False,
                "error": "Forbidden: Non-supervisors cannot upload or request schedules for other staff members",
            }), 403
        target_staff_id = g.current_user["staff_id"]
    else:
        target_staff_id = requested_id or g.current_user["staff_id"]

    month_str = request.form.get("month", "").strip() or "2026-09"
    infer_sections = request.form.get("infer_sections", "true").lower() in ("true", "1", "yes")

    job_id = str(uuid.uuid4())
    schedule_id = str(uuid.uuid4())

    save_job(job_id, schedule_id, target_staff_id, month_str, "processing")

    try:
        file_bytes = uploaded_file.read()
        client = OCRSpaceClient(timeout=45)
        ocr_result = client.parse_file(
            file_bytes=file_bytes,
            filename=uploaded_file.filename,
            ocr_engine=2,
            is_table=True,
            scale=True,
        )

        reconstructor = RosterTableReconstructor(
            words=ocr_result.get("words", []),
            month_str=month_str,
            requested_staff_id=target_staff_id,
        )
        recon = reconstructor.reconstruct()

        if infer_sections:
            recon = infer_section_shifts(recon)

        # Store draft schedule persistently in SQLite
        save_draft_schedule(
            schedule_id=schedule_id,
            staff_id=target_staff_id,
            month=month_str,
            reconstruction=recon,
            is_approved=False,
        )

        save_job(job_id, schedule_id, target_staff_id, month_str, "completed")

        return jsonify({
            "success": True,
            "job_id": job_id,
            "schedule_id": schedule_id,
            "status": "completed",
            "staff_id": target_staff_id,
            "month": month_str,
            "is_approved": False,
            "entries_count": len(recon.get("entries", [])),
            "unresolved_count": len(recon.get("unresolved_dates", [])),
        }), 201

    except Exception as exc:
        save_job(job_id, schedule_id, target_staff_id, month_str, "failed", error=str(exc))
        return jsonify({
            "success": False,
            "job_id": job_id,
            "error": str(exc),
        }), 500


@app.route("/api/roster/status/<job_id>", methods=["GET"])
@require_auth()
def api_roster_status(job_id):
    """Returns extraction job progress from SQLite."""
    job = get_job(job_id)
    if not job:
        return jsonify({"success": False, "error": "Job not found"}), 404
    # Authorization check
    if g.current_user["role"] != "supervisor" and job["staff_id"] != g.current_user["staff_id"]:
        return jsonify({"success": False, "error": "Forbidden: Cannot access another user's job"}), 403
    return jsonify({"success": True, "job": job}), 200


@app.route("/api/schedule/draft/<schedule_id>", methods=["GET"])
@require_auth()
def api_get_draft_schedule(schedule_id):
    """Retrieves full draft schedule from SQLite. Enforces per-user authorization."""
    draft = get_draft_schedule(schedule_id)
    if not draft:
        return jsonify({"success": False, "error": "Draft schedule not found"}), 404

    # Authorization: only owner or supervisor can view
    if g.current_user["role"] != "supervisor" and draft["staff_id"] != g.current_user["staff_id"]:
        return jsonify({"success": False, "error": "Forbidden: Cannot access another user's schedule"}), 403

    return jsonify({"success": True, "draft": draft}), 200


@app.route("/api/schedule/review", methods=["POST"])
@require_auth()
def api_review_cell():
    """Allows staff/supervisor to confirm or edit an individual cell in a draft schedule."""
    data = request.get_json(silent=True) or {}
    schedule_id = data.get("schedule_id")
    day_number = data.get("day_number")
    new_shift = data.get("shift")  # "M", "E", "N", "OFF"
    notes = data.get("notes")

    draft = get_draft_schedule(schedule_id)
    if not draft:
        return jsonify({"success": False, "error": "Schedule not found"}), 404

    # Authorization: cannot review another employee's schedule unless supervisor
    if g.current_user["role"] != "supervisor" and draft["staff_id"] != g.current_user["staff_id"]:
        return jsonify({"success": False, "error": "Forbidden: Cannot review another user's schedule"}), 403

    updated = update_draft_schedule_cell(schedule_id, int(day_number), new_shift, notes)
    if not updated:
        return jsonify({"success": False, "error": f"Day {day_number} not found in schedule"}), 404

    return jsonify({"success": True, "message": f"Day {day_number} reviewed and updated"}), 200


@app.route("/api/schedule/approve", methods=["POST"])
@require_auth()
def api_approve_schedule():
    """Supervisor approval: locks and activates the draft schedule into approved state."""
    data = request.get_json(silent=True) or {}
    schedule_id = data.get("schedule_id")

    # Strict server-side role check: only supervisors can approve schedules
    if g.current_user["role"] not in ("supervisor", "admin"):
        return jsonify({
            "success": False,
            "error": "Forbidden: Only authorized supervisors can approve and activate schedules",
        }), 403

    approved = approve_draft_schedule(schedule_id, g.current_user["staff_id"])
    if not approved:
        return jsonify({"success": False, "error": "Draft schedule not found"}), 404

    return jsonify({
        "success": True,
        "message": "Schedule approved and activated",
        "schedule_id": schedule_id,
        "is_approved": True,
        "approved_at": approved["approved_at"],
    }), 200


@app.route("/api/duty/manual", methods=["GET", "POST"])
@require_auth()
def api_manual_duties():
    """Lists or creates manual emergency duties and swaps in SQLite."""
    staff_id = g.current_user["staff_id"]
    if request.method == "GET":
        duties = get_manual_duties(staff_id)
        return jsonify({"success": True, "duties": duties}), 200

    data = request.get_json(silent=True) or {}
    date_str = data.get("date", "").strip()
    shift = data.get("shift", "").strip().upper()
    department = data.get("department", "").strip() or g.current_user.get("department", "Emergency ICU")
    floor = data.get("floor", "").strip() or "Floor 2"
    notes = data.get("notes", "").strip()

    if not date_str or not shift:
        return jsonify({"success": False, "error": "Missing 'date' or 'shift'"}), 400

    duty_id = str(uuid.uuid4())
    duty = add_manual_duty(
        duty_id=duty_id,
        staff_id=staff_id,
        date_str=date_str,
        shift=shift,
        department=department,
        floor=floor,
        notes=notes,
    )

    return jsonify({
        "success": True,
        "duty": duty,
    }), 201


@app.route("/api/schedule/revised/compare", methods=["POST"])
@require_auth()
def api_compare_revised_schedule():
    """
    Compares an approved active schedule against a revised draft roster.
    Returns per-date differences with explicit categorization.
    """
    data = request.get_json(silent=True) or {}
    revised_schedule_id = data.get("revised_schedule_id")
    revised_draft = get_draft_schedule(revised_schedule_id)
    if not revised_draft:
        return jsonify({"success": False, "error": "Revised draft schedule not found"}), 404

    staff_id = revised_draft["staff_id"]
    month = revised_draft["month"]

    # Authorization
    if g.current_user["role"] != "supervisor" and staff_id != g.current_user["staff_id"]:
        return jsonify({"success": False, "error": "Forbidden: Cannot access another user's schedule"}), 403

    current_approved = get_approved_schedule(staff_id, month)

    current_entries = []
    if current_approved:
        for e in current_approved["reconstruction"].get("entries", []):
            current_entries.append(
                ScheduleEntry(
                    date=e["date"],
                    display_label=e.get("normalized_shift") or "Off",
                    shift_code=e.get("normalized_shift"),
                    source_type=EntrySourceType.ROSTER,
                )
            )

    # Append manual duties as ScheduleEntries
    for md in get_manual_duties(staff_id):
        current_entries.append(
            ScheduleEntry(
                id=md["id"],
                date=md["date"],
                display_label=md["shift"],
                shift_code=md["shift"],
                department=md["department"],
                floor=md["floor"],
                source_type=EntrySourceType.MANUAL_EMERGENCY,
                notes=md.get("notes"),
            )
        )

    revised_cells = []
    for c in revised_draft["reconstruction"].get("entries", []):
        revised_cells.append(
            ExtractedCell(
                day_number=c["day_number"],
                date=c["date"],
                weekday=c["weekday"],
                raw_value=c.get("raw_value"),
                normalized_shift=c.get("normalized_shift"),
                review_status=ReviewStatus(c.get("review_status", "missing")),
            )
        )

    diffs = compute_schedule_revision_diff(current_entries, revised_cells)
    return jsonify({
        "success": True,
        "staff_id": staff_id,
        "month": month,
        "diff_count": len(diffs),
        "diffs": [d.to_dict() for d in diffs],
    }), 200


@app.route("/api/schedule/approved/<staff_id>/<month>", methods=["GET"])
@require_auth()
def api_get_approved_schedule(staff_id, month):
    """Retrieves active approved schedule for calendar view from SQLite."""
    # Authorization check
    if g.current_user["role"] != "supervisor" and staff_id != g.current_user["staff_id"]:
        return jsonify({"success": False, "error": "Forbidden: Cannot access another user's schedule"}), 403

    approved = get_approved_schedule(staff_id, month)
    if not approved:
        return jsonify({
            "success": False,
            "error": "No approved schedule found for this staff member and month",
            "is_approved": False,
        }), 404

    # Merge in manual duties
    manual = [d for d in get_manual_duties(staff_id) if d["date"].startswith(month)]
    return jsonify({
        "success": True,
        "schedule": approved,
        "manual_duties": manual,
    }), 200



if __name__ == "__main__":
    port = int(os.getenv("PORT", 5001))
    print(f"Medical Staff Schedule Tracker OCR Server starting on http://127.0.0.1:{port}")
    app.run(host="127.0.0.1", port=port, debug=False)
