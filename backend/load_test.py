"""Read-only HTTP benchmark. Tokens are supplied externally and never reported."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timezone
import json
import math
import os
from pathlib import Path
from threading import Barrier
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen


def percentile(values: list[float], quantile: float) -> float:
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * quantile) - 1)], 3) if ordered else 0.0


def benchmark(base_url: str, tokens: list[str], paths: list[str], concurrency: int,
              duration: float, timeout: float, opener=urlopen, interval: float = 0) -> dict:
    barrier = Barrier(concurrency + 1)
    deadline = [0.0]

    def worker(index):
        samples = []
        sequence = index
        barrier.wait()
        while time.perf_counter() < deadline[0]:
            path = paths[sequence % len(paths)]
            token = tokens[(index + sequence // len(paths)) % len(tokens)]
            sequence += 1
            started = time.perf_counter()
            status = "200"
            connection_error = None
            try:
                req = Request(base_url.rstrip("/") + path, headers={"Authorization": f"Bearer {token}"}, method="GET")
                with opener(req, timeout=timeout) as response:
                    response.read()
                    status = str(response.status)
            except HTTPError as exc:
                status = str(exc.code)
                exc.close()
            except (URLError, TimeoutError, OSError) as exc:
                status = "connection_error"
                reason = exc.reason if isinstance(exc, URLError) else exc
                # Types and numeric codes are diagnostic without exposing URLs/tokens.
                connection_error = f"{type(reason).__name__}:{getattr(reason, 'winerror', None) or getattr(reason, 'errno', None)}"
            samples.append((path.split("?")[0], (time.perf_counter() - started) * 1000, status, connection_error))
            if interval:
                time.sleep(min(interval, max(0, deadline[0] - time.perf_counter())))
        return samples

    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(worker, i) for i in range(concurrency)]
        started = time.perf_counter()
        deadline[0] = started + duration
        barrier.wait()
        samples = [sample for future in futures for sample in future.result()]
        elapsed = time.perf_counter() - started
    latencies = [latency for _, latency, _, _ in samples]
    statuses = Counter(status for _, _, status, _ in samples)
    connection_errors = Counter(reason for _, _, _, reason in samples if reason is not None)
    endpoints = {}
    for path in paths:
        endpoint = path.split("?")[0]
        subset = [(latency, status) for name, latency, status, _ in samples if name == endpoint]
        endpoints[endpoint] = {
            "requests": len(subset), "p50_ms": percentile([latency for latency, _ in subset], .5),
            "p95_ms": percentile([latency for latency, _ in subset], .95),
            "errors": sum(not status.startswith("2") for _, status in subset),
        }
    return {
        "concurrency": concurrency, "elapsed_seconds": round(elapsed, 3), "requests": len(samples),
        "requests_per_second": round(len(samples) / elapsed, 3),
        "successful_requests_per_second": round(sum(count for status, count in statuses.items() if status.startswith("2")) / elapsed, 3),
        "p50_ms": percentile(latencies, .5), "p95_ms": percentile(latencies, .95),
        "errors": sum(count for status, count in statuses.items() if not status.startswith("2")),
        "statuses": dict(statuses), "connection_errors": dict(connection_errors), "endpoints": endpoints,
    }


def compare(report: dict, baseline: dict) -> list[dict]:
    previous = {run["concurrency"]: run for run in baseline["runs"]}
    result = []
    for run in report["runs"]:
        old = previous.get(run["concurrency"])
        if old is not None:
            result.append({
                "concurrency": run["concurrency"],
                "requests_per_second_delta": round(run["requests_per_second"] - old["requests_per_second"], 3),
                "p95_ms_delta": round(run["p95_ms"] - old["p95_ms"], 3),
                "errors_delta": run["errors"] - old["errors"],
            })
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.environ.get("LOAD_TEST_BASE_URL"))
    parser.add_argument("--tokens-file", type=Path, help="JSON array of access tokens; never commit this file")
    parser.add_argument("--duration", type=float, default=60)
    parser.add_argument("--concurrency", type=int, nargs="+", default=[1, 10, 25])
    parser.add_argument("--timeout", type=float, default=10)
    parser.add_argument("--interval", type=float, default=0, help="Pause between requests per user, in seconds; default 0 (stress)")
    parser.add_argument("--from-date", default=date.today().isoformat())
    parser.add_argument("--to-date")
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    parsed = urlparse(args.base_url or "")
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        parser.error("Provide an HTTP base URL without credentials or query parameters")
    if any(not math.isfinite(n) for n in (args.duration, args.timeout, args.interval)) or args.duration <= 0 or args.timeout <= 0 or args.interval < 0 or any(n <= 0 for n in args.concurrency):
        parser.error("Duration, timeout and concurrency must be positive")
    try:
        first = date.fromisoformat(args.from_date)
        last = date.fromisoformat(args.to_date) if args.to_date else first
        if last < first or (last - first).days > 366:
            raise ValueError()
    except ValueError:
        parser.error("Provide an ordered ISO date range of at most 366 days")
    try:
        raw = args.tokens_file.read_text(encoding="utf-8") if args.tokens_file else os.environ.get("LOAD_TEST_TOKENS", "[]")
        tokens = json.loads(raw)
    except (ValueError, OSError):
        parser.error("Tokens must be supplied as a JSON array")
    if not isinstance(tokens, list) or not tokens or any(not isinstance(t, str) or not t.strip() or "\n" in t or "\r" in t for t in tokens):
        parser.error("Provide at least one non-empty access token")
    query = urlencode({"from": first.isoformat(), "to": last.isoformat()})
    paths = [f"/api/tasks?{query}", f"/api/calendar?{query}", f"/api/bootstrap?{query}", "/api/routines"]
    report = {"generated_at": datetime.now(timezone.utc).isoformat(), "duration_seconds": args.duration, "interval_seconds": args.interval,
              "date_range": {"from": first.isoformat(), "to": last.isoformat()}, "runs": []}
    baseline = None
    if args.baseline:
        try:
            baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
        except (ValueError, OSError):
            parser.error("Provide a readable JSON baseline report")
        if not isinstance(baseline, dict) or not isinstance(baseline.get("runs"), list):
            parser.error("Provide a benchmark baseline report")
        if baseline.get("duration_seconds") != args.duration or baseline.get("date_range") != report["date_range"] or baseline.get("interval_seconds", 0) != args.interval:
            parser.error("Baseline must use the same duration, date range and interval")
        if [run.get("concurrency") for run in baseline["runs"]] != args.concurrency:
            parser.error("Baseline must use the same concurrency levels")
    for concurrency in args.concurrency:
        run = benchmark(args.base_url, tokens, paths, concurrency, args.duration, args.timeout, interval=args.interval)
        report["runs"].append(run)
        print(json.dumps(run, ensure_ascii=False), flush=True)
    if baseline is not None:
        report["comparison"] = compare(report, baseline)
        print(json.dumps({"comparison": report["comparison"]}, ensure_ascii=False))
    if args.output:
        args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return 1 if any(run["errors"] for run in report["runs"]) else 0


if __name__ == "__main__":
    raise SystemExit(main())
