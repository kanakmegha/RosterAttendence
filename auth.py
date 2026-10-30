"""
MedicalStaffScheduleTracker - Authentication and Authorization Layer
Provides token-based authentication and per-user authorization for REST endpoints.
"""

from functools import wraps
import hashlib
import hmac
import json
import os
import time
from typing import Optional, Dict, Any
from flask import request, jsonify, g

JWT_SECRET = os.getenv("JWT_SECRET", "medical-schedule-tracker-secret-key-2026")

# Mock staff database for authentication and profile resolution
STAFF_USERS = {
    "ABHL341": {
        "staff_id": "ABHL341",
        "name": "Akash Kumar",
        "role": "staff",
        "department": "ICU / Emergency",
        "floor": "Floor 2",
        "password_hash": hashlib.sha256("password123".encode()).hexdigest(),
    },
    "ABHL217": {
        "staff_id": "ABHL217",
        "name": "Pooja Sharma",
        "role": "staff",
        "department": "General Medicine",
        "floor": "Floor 3",
        "password_hash": hashlib.sha256("password123".encode()).hexdigest(),
    },
    "SUPER01": {
        "staff_id": "SUPER01",
        "name": "Dr. Sarah Admin",
        "role": "supervisor",
        "department": "Medical Administration",
        "floor": "Floor 1",
        "password_hash": hashlib.sha256("admin123".encode()).hexdigest(),
    },
}


def generate_token(user_data: Dict[str, Any], expires_in: int = 86400) -> str:
    """Generates a secure HMAC-SHA256 signed token."""
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "staff_id": user_data["staff_id"],
        "name": user_data["name"],
        "role": user_data["role"],
        "department": user_data.get("department", ""),
        "exp": int(time.time()) + expires_in,
    }
    header_bytes = json.dumps(header, separators=(',', ':')).encode()
    payload_bytes = json.dumps(payload, separators=(',', ':')).encode()

    import base64
    b64_header = base64.urlsafe_b64encode(header_bytes).decode().rstrip("=")
    b64_payload = base64.urlsafe_b64encode(payload_bytes).decode().rstrip("=")

    message = f"{b64_header}.{b64_payload}".encode()
    signature = hmac.new(JWT_SECRET.encode(), message, hashlib.sha256).digest()
    b64_sig = base64.urlsafe_b64encode(signature).decode().rstrip("=")

    return f"{b64_header}.{b64_payload}.{b64_sig}"


def verify_token(token: str) -> Optional[Dict[str, Any]]:
    """Verifies a signed token and returns the payload if valid."""
    try:
        import base64
        parts = token.split(".")
        if len(parts) != 3:
            return None
        b64_header, b64_payload, b64_sig = parts

        message = f"{b64_header}.{b64_payload}".encode()
        expected_sig = hmac.new(JWT_SECRET.encode(), message, hashlib.sha256).digest()
        actual_sig = base64.urlsafe_b64decode(b64_sig + "==")

        if not hmac.compare_digest(expected_sig, actual_sig):
            return None

        payload_json = base64.urlsafe_b64decode(b64_payload + "==").decode()
        payload = json.loads(payload_json)

        if payload.get("exp", 0) < time.time():
            return None  # Expired

        return payload
    except Exception:
        return None


from db import authenticate_user_db, init_db

# Initialize database schema on startup
init_db()



def authenticate_user(username_or_id: str, password: str) -> Optional[Dict[str, Any]]:
    """Authenticates credentials against SQLite database."""
    return authenticate_user_db(username_or_id, password)



def require_auth(allowed_roles: Optional[list] = None):
    """
    Decorator enforcing Bearer token authentication and role-based authorization.
    Attaches current user payload to Flask `g.current_user`.
    """
    def decorator(f):
        @wraps(f)
        def decorated_function(*args, **kwargs):
            auth_header = request.headers.get("Authorization")
            if not auth_header or not auth_header.startswith("Bearer "):
                return jsonify({"error": "Missing or invalid Authorization header"}), 401

            token = auth_header.split(" ", 1)[1].strip()
            user = verify_token(token)
            if not user:
                return jsonify({"error": "Invalid or expired authorization token"}), 401

            if allowed_roles and user.get("role") not in allowed_roles:
                return jsonify({"error": "Insufficient permissions for this resource"}), 403

            g.current_user = user
            return f(*args, **kwargs)
        return decorated_function
    return decorator
