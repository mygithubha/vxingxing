# Auto Connect Node Monitor — collector prototype

This module is the first isolated stage of the Yudou daily-node monitor.

## Rules

- Target date is China-local today minus two calendar days.
- The collector must find an article whose title contains that exact date.
- It never substitutes a neighboring date.
- It extracts only directly published HTTPS subscription URLs on `www.yudou89.shop` whose path contains the target date and whose suffix is `.txt`, `.yaml`, or `.yml`.
- It does not submit passwords, run page JavaScript, or bypass encrypted content.
- Subscription URLs are returned to the caller in memory only. CLI output intentionally prints metadata and subscription types, never the URLs.

## Local test

```bash
python -m unittest discover -s tests -v
```

## Manual collection

```bash
python collector.py --date 2026-11-04
```

The CLI prints a redacted summary. The current prototype does not persist results, expose an API, or import anything into the Android client. Those require the private storage/authenticated API stage and explicit user confirmation in the app.

## Security boundary for the next stage

- Never commit raw subscription URLs or fetched subscription bodies.
- Never put them in GitHub Actions logs or public artifacts.
- Store secrets in a private server-side store; authenticate app requests.
- Return only the selected pending record to the authenticated app.
- Keep collected subscriptions in a staging/pending area separate from active profiles.
- Require an explicit user action before import; back up active configuration and support rollback.
