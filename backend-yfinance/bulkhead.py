import threading


class BulkheadSaturatedError(Exception):
    pass


class LoaderBulkhead:
    """Bound concurrent loaders without holding a coordination lock while waiting or loading."""

    def __init__(self, max_active, acquire_timeout_seconds):
        if max_active <= 0:
            raise ValueError("max_active must be positive")
        if acquire_timeout_seconds < 0:
            raise ValueError("acquire_timeout_seconds must be non-negative")
        self.max_active = max_active
        self.acquire_timeout_seconds = acquire_timeout_seconds
        self._permits = threading.BoundedSemaphore(max_active)
        self._state_lock = threading.Lock()
        self._active_count = 0

    def call(self, loader):
        acquired = self._permits.acquire(timeout=self.acquire_timeout_seconds)
        if not acquired:
            raise BulkheadSaturatedError("yfinance loader bulkhead is saturated")

        counted = False
        try:
            with self._state_lock:
                self._active_count += 1
                counted = True
            return loader()
        finally:
            if counted:
                with self._state_lock:
                    self._active_count -= 1
            self._permits.release()

    @property
    def active_count(self):
        with self._state_lock:
            return self._active_count
