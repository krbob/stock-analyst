import math
import threading
import time
from collections import deque
from dataclasses import dataclass
from enum import Enum


class CircuitState(Enum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


class CircuitOutcome(Enum):
    HEALTHY = "healthy"
    FAILURE = "failure"
    FORCE_OPEN = "force_open"
    NEUTRAL = "neutral"


class CircuitOpenError(Exception):
    def __init__(self, retry_after_seconds):
        super().__init__("yfinance upstream circuit is open")
        self.retry_after_seconds = retry_after_seconds


@dataclass(frozen=True)
class _Attempt:
    generation: int
    probe: bool


class CircuitBreaker:
    """Concurrency-safe circuit breaker with one half-open probe and stale-result protection."""

    def __init__(
        self,
        failure_threshold,
        failure_window_seconds,
        open_seconds,
        *,
        forced_open_seconds=None,
        clock=time.monotonic,
    ):
        if failure_threshold <= 0:
            raise ValueError("failure_threshold must be positive")
        if failure_window_seconds <= 0:
            raise ValueError("failure_window_seconds must be positive")
        if open_seconds <= 0:
            raise ValueError("open_seconds must be positive")
        if forced_open_seconds is not None and forced_open_seconds <= 0:
            raise ValueError("forced_open_seconds must be positive")
        self.failure_threshold = failure_threshold
        self.failure_window_seconds = failure_window_seconds
        self.open_seconds = open_seconds
        self.forced_open_seconds = forced_open_seconds or open_seconds
        self._clock = clock
        self._lock = threading.Lock()
        self._state = CircuitState.CLOSED
        self._generation = 0
        self._failure_times = deque()
        self._open_until = None

    def call(self, loader, classify_error):
        attempt = self._acquire_attempt()
        try:
            result = loader()
        except BaseException as error:
            try:
                outcome = classify_error(error)
            except BaseException:
                self._complete(attempt, CircuitOutcome.NEUTRAL)
                raise
            self._complete(attempt, outcome)
            raise
        else:
            self._complete(attempt, CircuitOutcome.HEALTHY)
            return result

    def _acquire_attempt(self):
        with self._lock:
            now = self._clock()
            if self._state == CircuitState.OPEN:
                if now < self._open_until:
                    raise CircuitOpenError(self._retry_after(now))
                self._state = CircuitState.HALF_OPEN
                self._generation += 1
                return _Attempt(generation=self._generation, probe=True)
            if self._state == CircuitState.HALF_OPEN:
                raise CircuitOpenError(1)
            return _Attempt(generation=self._generation, probe=False)

    def _complete(self, attempt, outcome):
        with self._lock:
            now = self._clock()
            if outcome == CircuitOutcome.FORCE_OPEN:
                self._open(now, self.forced_open_seconds)
                return
            if attempt.generation != self._generation:
                return
            if attempt.probe:
                if self._state != CircuitState.HALF_OPEN:
                    return
                if outcome == CircuitOutcome.HEALTHY:
                    self._close()
                else:
                    self._open(now)
                return
            if self._state != CircuitState.CLOSED:
                return
            if outcome == CircuitOutcome.HEALTHY:
                self._failure_times.clear()
            elif outcome == CircuitOutcome.FAILURE:
                self._prune_failures(now)
                self._failure_times.append(now)
                if len(self._failure_times) >= self.failure_threshold:
                    self._open(now)

    def _prune_failures(self, now):
        cutoff = now - self.failure_window_seconds
        while self._failure_times and self._failure_times[0] <= cutoff:
            self._failure_times.popleft()

    def _open(self, now, duration=None):
        self._state = CircuitState.OPEN
        self._open_until = now + (duration or self.open_seconds)
        self._failure_times.clear()
        self._generation += 1

    def _close(self):
        self._state = CircuitState.CLOSED
        self._open_until = None
        self._failure_times.clear()
        self._generation += 1

    def _retry_after(self, now):
        return max(1, math.ceil(self._open_until - now))

    def reset(self):
        with self._lock:
            self._state = CircuitState.CLOSED
            self._open_until = None
            self._failure_times.clear()
            self._generation += 1

    @property
    def state(self):
        with self._lock:
            return self._state

    @property
    def failure_count(self):
        with self._lock:
            now = self._clock()
            if self._state == CircuitState.CLOSED:
                self._prune_failures(now)
            return len(self._failure_times)

    @property
    def generation(self):
        with self._lock:
            return self._generation
