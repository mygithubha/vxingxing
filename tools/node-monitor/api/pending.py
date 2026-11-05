from __future__ import annotations

import hmac
import json
import os
from datetime import date, datetime, timedelta
from http.server import BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlparse
from zoneinfo import ZoneInfo

from vercel.blob import sync as blob


def _authorized(header: str | None) -> bool:
    secret = os.environ.get("NODE_MONITOR_API_TOKEN", "")
    if not secret or not header or not header.startswith("Bearer "):
        return False
    return hmac.compare_digest(header[7:].strip(), secret)


def _default_date() -> str:
    return (datetime.now(ZoneInfo("Asia/Shanghai")).date() - timedelta(days=2)).isoformat()


class handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if not _authorized(self.headers.get("Authorization")):
            self.send_error(401)
            return
        query = parse_qs(urlparse(self.path).query)
        target = (query.get("date") or [_default_date()])[0]
        try:
            date.fromisoformat(target)
        except ValueError:
            self.send_error(400, "Invalid date")
            return

        try:
            stored = blob.get_blob(f"pending/{target}.json", access="private")
            record = json.loads(stored.body.decode("utf-8"))
            requested_type = (query.get("type") or [None])[0]

            if requested_type is None:
                # Metadata only: never return subscription URLs in the listing response.
                response = {
                    "target_date": record.get("target_date"),
                    "status": record.get("status"),
                    "article_title": record.get("article_title"),
                    "article_url": record.get("article_url"),
                    "collected_at": record.get("collected_at"),
                    "subscriptions": [
                        {
                            "kind": item.get("kind"),
                            "valid": item.get("valid"),
                            "node_count": item.get("node_count"),
                        }
                        for item in record.get("subscriptions", [])
                    ],
                }
            else:
                if requested_type not in {"txt", "yaml"}:
                    self.send_error(400, "Unsupported subscription type")
                    return
                item = next(
                    (x for x in record.get("subscriptions", [])
                     if x.get("kind") == requested_type and x.get("valid") is True),
                    None,
                )
                if item is None:
                    self.send_error(404, "No valid subscription of requested type")
                    return
                # Reveal the URL only after an authenticated, explicit type-specific request
                # made by the app after the user taps Import.
                response = {
                    "target_date": target,
                    "kind": requested_type,
                    "subscription_url": item["url"],
                    "node_count": item.get("node_count"),
                }

            body = json.dumps(response, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "private, no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(body)
        except Exception as exc:
            # Missing object and storage failures do not disclose storage details.
            self.send_response(404 if type(exc).__name__ == "BlobNotFoundError" else 503)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(json.dumps({"error": "pending_record_unavailable"}).encode("utf-8"))

    def log_message(self, format, *args):
        return
