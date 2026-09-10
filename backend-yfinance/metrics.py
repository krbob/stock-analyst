import threading
from collections import defaultdict

from prometheus_client import CollectorRegistry, ProcessCollector, generate_latest


_DURATION_BUCKETS = (
    ("0.01", 0.01),
    ("0.05", 0.05),
    ("0.1", 0.1),
    ("0.25", 0.25),
    ("0.5", 0.5),
    ("1", 1.0),
    ("2.5", 2.5),
    ("5", 5.0),
    ("10", 10.0),
    ("30", 30.0),
)
_HTTP_STATUS_CLASSES = ("1xx", "2xx", "3xx", "4xx", "429", "5xx", "other")


class AdapterMetrics:
    """Application metrics with bounded label domains."""

    def __init__(self):
        self._lock = threading.Lock()
        self.reset()

    def reset(self):
        with self._lock:
            self._http_counts = defaultdict(int)
            self._http_responses = dict.fromkeys(_HTTP_STATUS_CLASSES, 0)
            self._http_duration_sums = defaultdict(float)
            self._http_duration_buckets = defaultdict(lambda: [0] * len(_DURATION_BUCKETS))
            self._cache_lookups = defaultdict(int)
            self._bulkhead_rejections = 0
            self._circuit_rejections = 0
            self._circuit_transitions = defaultdict(int)

    def record_http(self, method, route, status, duration_seconds):
        key = (method, route, int(status))
        duration_seconds = max(0.0, float(duration_seconds))
        with self._lock:
            self._http_counts[key] += 1
            status_class = "429" if key[2] == 429 else f"{key[2] // 100}xx"
            self._http_responses[status_class if status_class in self._http_responses else "other"] += 1
            self._http_duration_sums[key] += duration_seconds
            buckets = self._http_duration_buckets[key]
            for index, (_label, upper_bound) in enumerate(_DURATION_BUCKETS):
                if duration_seconds <= upper_bound:
                    buckets[index] += 1

    def record_cache_lookup(self, cache, result):
        with self._lock:
            self._cache_lookups[(cache, result)] += 1

    def record_bulkhead_rejection(self):
        with self._lock:
            self._bulkhead_rejections += 1

    def record_circuit_rejection(self):
        with self._lock:
            self._circuit_rejections += 1

    def record_circuit_transition(self, previous, current, reason):
        with self._lock:
            self._circuit_transitions[(previous.value, current.value, reason)] += 1

    def render(self):
        with self._lock:
            http_counts = dict(self._http_counts)
            http_responses = dict(self._http_responses)
            duration_sums = dict(self._http_duration_sums)
            duration_buckets = {
                key: tuple(values) for key, values in self._http_duration_buckets.items()
            }
            cache_lookups = dict(self._cache_lookups)
            bulkhead_rejections = self._bulkhead_rejections
            circuit_rejections = self._circuit_rejections
            circuit_transitions = dict(self._circuit_transitions)

        lines = [
            "# HELP stock_analyst_yfinance_http_responses_total Completed adapter responses by status class, initialized at zero.",
            "# TYPE stock_analyst_yfinance_http_responses_total counter",
        ]
        for status_class, count in http_responses.items():
            lines.append(
                "stock_analyst_yfinance_http_responses_total"
                f'{_labels(status_class=status_class)} {count}'
            )
        lines.extend([
            "# HELP stock_analyst_yfinance_http_requests_total Completed adapter requests.",
            "# TYPE stock_analyst_yfinance_http_requests_total counter",
        ])
        for key in sorted(http_counts):
            method, route, status = key
            labels = _labels(method=method, route=route, status=str(status))
            lines.append(f"stock_analyst_yfinance_http_requests_total{labels} {http_counts[key]}")

        lines.extend(
            (
                "# HELP stock_analyst_yfinance_http_request_duration_seconds Adapter request latency.",
                "# TYPE stock_analyst_yfinance_http_request_duration_seconds histogram",
            )
        )
        for key in sorted(http_counts):
            method, route, status = key
            base_labels = {"method": method, "route": route, "status": str(status)}
            for index, (label, _upper_bound) in enumerate(_DURATION_BUCKETS):
                labels = _labels(**base_labels, le=label)
                lines.append(
                    "stock_analyst_yfinance_http_request_duration_seconds_bucket"
                    f"{labels} {duration_buckets[key][index]}"
                )
            labels = _labels(**base_labels, le="+Inf")
            lines.append(
                "stock_analyst_yfinance_http_request_duration_seconds_bucket"
                f"{labels} {http_counts[key]}"
            )
            labels = _labels(**base_labels)
            lines.append(
                "stock_analyst_yfinance_http_request_duration_seconds_sum"
                f"{labels} {duration_sums[key]:.9f}"
            )
            lines.append(
                "stock_analyst_yfinance_http_request_duration_seconds_count"
                f"{labels} {http_counts[key]}"
            )

        lines.extend(
            (
                "# HELP stock_analyst_yfinance_cache_lookups_total Cache lookup outcomes.",
                "# TYPE stock_analyst_yfinance_cache_lookups_total counter",
            )
        )
        for key in sorted(cache_lookups):
            cache, result = key
            lines.append(
                "stock_analyst_yfinance_cache_lookups_total"
                f"{_labels(cache=cache, result=result)} {cache_lookups[key]}"
            )

        lines.extend(
            (
                "# HELP stock_analyst_yfinance_bulkhead_rejections_total Rejected unique loaders.",
                "# TYPE stock_analyst_yfinance_bulkhead_rejections_total counter",
                f"stock_analyst_yfinance_bulkhead_rejections_total {bulkhead_rejections}",
                "# HELP stock_analyst_yfinance_circuit_rejections_total Calls rejected by the circuit.",
                "# TYPE stock_analyst_yfinance_circuit_rejections_total counter",
                f"stock_analyst_yfinance_circuit_rejections_total {circuit_rejections}",
                "# HELP stock_analyst_yfinance_circuit_transitions_total Circuit state transitions.",
                "# TYPE stock_analyst_yfinance_circuit_transitions_total counter",
            )
        )
        for key in sorted(circuit_transitions):
            previous, current, reason = key
            lines.append(
                "stock_analyst_yfinance_circuit_transitions_total"
                f"{_labels(from_state=previous, to_state=current, reason=reason)} "
                f"{circuit_transitions[key]}"
            )
        return "\n".join(lines) + "\n"


class ProcessMetrics:
    """Standard Linux process metrics, isolated from the global registry."""

    def __init__(self, proc="/proc"):
        self._registry = CollectorRegistry()
        ProcessCollector(proc=proc, registry=self._registry)

    def render(self):
        return generate_latest(self._registry).decode("utf-8")


def _labels(**labels):
    values = [f'{name}="{_escape(value)}"' for name, value in labels.items()]
    return "{" + ",".join(values) + "}"


def _escape(value):
    return str(value).replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')
