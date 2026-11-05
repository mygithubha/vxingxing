from __future__ import annotations

import hmac
import json
import os
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from fastapi import Body, FastAPI, Header, HTTPException, Query, Response
from vercel.blob import sync as blob
from vercel.blob.errors import BlobNotFoundError

from collector import collect, default_target_date

app = FastAPI(title="Auto Connect Node Monitor", docs_url=None, redoc_url=None)


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



@app.post("/api/pair")
def pair_device(payload: dict = Body(...), response: Response = None):
    # Exchange a high-entropy setup code for the long-lived API credential.
    expected = os.environ.get("PAIRING_CODE", "")
    api_token = os.environ.get("NODE_MONITOR_API_TOKEN", "")
    supplied = str(payload.get("pairing_code", "")).strip()
    if not expected or not api_token or not hmac.compare_digest(supplied, expected):
        raise HTTPException(status_code=401, detail="Pairing failed")
    response.headers["Cache-Control"] = "no-store"
    response.headers["Pragma"] = "no-cache"
    return {"access_token": api_token}

@app.get("/api/cron")
def run_daily_collection(
    response: Response,
    authorization: str | None = Header(default=None),
):
    if not _authorized(authorization, "CRON_SECRET"):
        raise HTTPException(status_code=401, detail="Unauthorized")
    try:
        target = default_target_date()
        result = collect(target)
        payload = json.dumps(_record(result), ensure_ascii=False).encode("utf-8")
        # Private Blob only. Never log payload, subscription URLs, or Blob URLs.
        blob.put_blob(
            f"pending/{target.isoformat()}.json",
            payload,
            access="private",
            content_type="application/json",
            allow_overwrite=True,
        )
        if response is not None:
            response.headers["Cache-Control"] = "no-store"
        return {
            "target_date": target.isoformat(),
            "status": result.status,
            "subscription_count": len(result.subscriptions),
        }
    except Exception as exc:
        # Keep logs and error responses free of URLs, response bodies, and credentials.
        raise HTTPException(status_code=500, detail=type(exc).__name__) from None


@app.get("/api/pending")
def get_pending_record(
    response: Response,
    authorization: str | None = Header(default=None),
    target_date: str | None = Query(default=None, alias="date"),
    subscription_type: str | None = Query(default=None, alias="type"),
):
    if not _authorized(authorization, "NODE_MONITOR_API_TOKEN"):
        raise HTTPException(status_code=401, detail="Unauthorized")

    target = target_date or (
        datetime.now(ZoneInfo("Asia/Shanghai")).date() - timedelta(days=2)
    ).isoformat()
    try:
        date.fromisoformat(target)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid date") from None

    if subscription_type is not None and subscription_type not in {"txt", "yaml"}:
        raise HTTPException(status_code=400, detail="Unsupported subscription type")

    try:
        stored = blob.get_blob(f"pending/{target}.json", access="private")
        record = json.loads(stored.body.decode("utf-8"))
    except BlobNotFoundError:
        raise HTTPException(status_code=404, detail="No pending record") from None
    except Exception:
        raise HTTPException(status_code=503, detail="Pending record unavailable") from None

    response.headers["Cache-Control"] = "private, no-store"
    response.headers["X-Content-Type-Options"] = "nosniff"

    if subscription_type is None:
        # Metadata only: subscription URLs never appear in the listing response.
        return {
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

    item = next(
        (
            entry for entry in record.get("subscriptions", [])
            if entry.get("kind") == subscription_type and entry.get("valid") is True
        ),
        None,
    )
    if item is None:
        raise HTTPException(status_code=404, detail="No valid subscription of requested type")
    # Reveal a URL only after an authenticated, type-specific request from the app
    # made after the user explicitly taps Import.
    return {
        "target_date": target,
        "kind": subscription_type,
        "subscription_url": item["url"],
        "node_count": item.get("node_count"),
    }
