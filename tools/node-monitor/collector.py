#!/usr/bin/env python3
"""Date-scoped collector for the public Yudou daily-node article.

Security properties:
- Only follows an article whose title contains the exact target date.
- Only extracts HTTPS subscription URLs hosted on yudou89.shop.
- Does not submit passwords, execute page JavaScript, or bypass article encryption.
- Never prints subscription URLs; callers receive them in memory for protected storage.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import asdict, dataclass
from datetime import date, datetime, timedelta
from html.parser import HTMLParser
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

CATEGORY_URL = "https://www.yudou789.top/category/jiedian"
ALLOWED_SUBSCRIPTION_HOST = "www.yudou89.shop"
MAX_PAGE_BYTES = 3 * 1024 * 1024
USER_AGENT = "AutoConnectNodeMonitor/1.0 (+date-scoped public-page checker)"
DATE_TITLE_RE = re.compile(r"(20\d{2})年(\d{1,2})月(\d{1,2})日")
URL_RE = re.compile(r"https?://[^\\s\\\"'<>]+", re.IGNORECASE)


@dataclass(frozen=True)
class Article:
    target_date: str
    url: str
    title: str


@dataclass(frozen=True)
class Subscription:
    kind: str
    url: str


@dataclass(frozen=True)
class CollectionResult:
    target_date: str
    status: str
    article_url: str | None
    article_title: str | None
    subscriptions: tuple[Subscription, ...]
    message: str


class AnchorCollector(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.anchors: list[tuple[str, str]] = []
        self._href: str | None = None
        self._parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() == "a":
            self._href = dict(attrs).get("href")
            self._parts = []

    def handle_data(self, data: str) -> None:
        if self._href is not None:
            self._parts.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "a" and self._href is not None:
            self.anchors.append((self._href, " ".join(self._parts).strip()))
            self._href = None
            self._parts = []


def _date_from_title(value: str) -> date | None:
    match = DATE_TITLE_RE.search(value)
    if not match:
        return None
    try:
        return date(int(match.group(1)), int(match.group(2)), int(match.group(3)))
    except ValueError:
        return None


def find_exact_article(category_html: str, target: date, base_url: str = CATEGORY_URL) -> Article | None:
    parser = AnchorCollector()
    parser.feed(category_html)
    for href, title in parser.anchors:
        if _date_from_title(title) != target:
            continue
        article_url = urljoin(base_url, href)
        parsed = urlparse(article_url)
        if parsed.scheme != "https" or parsed.hostname != urlparse(base_url).hostname:
            continue
        if not re.fullmatch(r"/[0-9]+\\.html", parsed.path):
            continue
        return Article(target.isoformat(), article_url, title)
    return None


def extract_public_subscriptions(article_html: str, target: date) -> tuple[Subscription, ...]:
    """Extract only directly published URLs; never execute password/decryption scripts."""
    parser = AnchorCollector()
    parser.feed(article_html)
    candidates = [data for data in re.findall(r"https?://[^\\s\\\"'<>]+", article_html, re.IGNORECASE)]
    candidates.extend(href for href, _ in parser.anchors if href and href.startswith("https://"))
    found: dict[str, Subscription] = {}
    expected_date = target.strftime("%Y%m%d")

    for raw in candidates:
        candidate = raw.rstrip(".,);]")
        parsed = urlparse(candidate)
        if parsed.scheme != "https" or parsed.hostname != ALLOWED_SUBSCRIPTION_HOST:
            continue
        if expected_date not in parsed.path:
            continue
        suffix = parsed.path.lower().rsplit(".", 1)[-1] if "." in parsed.path else ""
        kind = "txt" if suffix == "txt" else "yaml" if suffix in {"yaml", "yml"} else ""
        if not kind:
            continue
        found[candidate] = Subscription(kind, candidate)

    return tuple(found[url] for url in sorted(found, key=lambda u: (found[u].kind, u)))


def _fetch(url: str, timeout: int = 20) -> str:
    request = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "text/html,*/*;q=0.8"})
    with urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP status {response.status}")
        payload = response.read(MAX_PAGE_BYTES + 1)
    if len(payload) > MAX_PAGE_BYTES:
        raise RuntimeError("page exceeded size limit")
    return payload.decode("utf-8", errors="replace")


def collect(target: date, fetcher=_fetch) -> CollectionResult:
    try:
        category_html = fetcher(CATEGORY_URL)
        article = find_exact_article(category_html, target)
        if article is None:
            return CollectionResult(target.isoformat(), "not_found", None, None, (), "No exact-date article found")
        article_html = fetcher(article.url)
        subscriptions = extract_public_subscriptions(article_html, target)
        if not subscriptions:
            return CollectionResult(
                target.isoformat(), "protected_or_missing", article.url, article.title, (),
                "No directly published subscription URL found; no password or script bypass attempted",
            )
        return CollectionResult(target.isoformat(), "success", article.url, article.title, subscriptions, "Public URLs extracted")
    except (HTTPError, URLError, TimeoutError, OSError, RuntimeError, ValueError) as exc:
        # Do not include response bodies or subscription URLs in error messages.
        return CollectionResult(target.isoformat(), "error", None, None, (), type(exc).__name__)


def default_target_date(now: datetime | None = None) -> date:
    local_now = now.astimezone(ZoneInfo("Asia/Shanghai")) if now else datetime.now(ZoneInfo("Asia/Shanghai"))
    return local_now.date() - timedelta(days=2)


def main() -> int:
    parser = argparse.ArgumentParser(description="Collect publicly exposed subscriptions from the exact date's article.")
    parser.add_argument("--date", help="Target date YYYY-MM-DD; defaults to China date minus two days")
    args = parser.parse_args()
    try:
        target = date.fromisoformat(args.date) if args.date else default_target_date()
    except ValueError:
        parser.error("--date must be YYYY-MM-DD")
    result = collect(target)
    # Deliberately omit subscription URLs from stdout/logs.
    print(json.dumps({
        "target_date": result.target_date,
        "status": result.status,
        "article_url": result.article_url,
        "article_title": result.article_title,
        "subscription_types": [item.kind for item in result.subscriptions],
        "subscription_count": len(result.subscriptions),
        "message": result.message,
    }, ensure_ascii=False))
    return 0 if result.status == "success" else 1


if __name__ == "__main__":
    sys.exit(main())
