from __future__ import annotations

import hmac
import json
import os
from datetime import datetime
from http.server import BaseHTTPRequestHandler
from zoneinfo import ZoneInfo

from vercel.blob import sync as blob

from collector import collect, default_target_date


def _authorized(header: str | None, secret_name: str) -> bool:
    secret = os.environ.get(secret_name, "")
    if not secret or not header or not header.startswith("Bearer "):
        return False
    return hmac.compare_digest(header[7:].strip(), secret)


def _record(result) -> dict:
    return {
        "target_date": result.target_date,
        "status": result.status,
        "article_url": result.article_url,
        "article_title": result.article_title,
        "message": result.message,
        "collected_at": datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(),
        "subscriptions": [
            {
                "kind": item.kind,
                "url": item.url,
                "valid": item.valid,
                "node_count": item.node_count,
            }
            for item in result.subscriptions
        ],
    }


class handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if not _authorized(self.headers.get("Authorization"), "CRON_SECRET"):
            self.send_error(401)
            return
        try:
            target = default_target_date()
            result = collect(target)
            payload = json.dumps(_record(result), ensure_ascii=False).encode("utf-8")
            # Private Blob only. Never print payload, subscription URLs, or Blob URLs to logs.
            blob.put_blob(
                f"pending/{target.isoformat()}.json",
                payload,
                access="private",
                content_type="application/json",
                allow_overwrite=True,
            )
            response = json.dumps({
                "target_date": target.isoformat(),
                "status": result.status,
                "subscription_count": len(result.subscriptions),
            }).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(response)
        except Exception as exc:
            # Log only the exception class; never log URLs, bodies, or credentials.
            self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(json.dumps({"error": type(exc).__name__}).encode("utf-8"))

    def log_message(self, format, *args):
        # Suppress request query/header logging on the credential-bearing endpoint.
        return
