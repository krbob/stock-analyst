import copy
import sys
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass, fields, is_dataclass


# Conservative allowance for the OrderedDict node, _CacheEntry, expiry float and size integer.
_ENTRY_OVERHEAD_BYTES = 256


def estimate_retained_bytes(value):
    """Estimate the Python heap retained by supported cache payloads without serialising them."""
    seen = set()

    def estimate(current):
        identity = id(current)
        if identity in seen:
            return 0
        seen.add(identity)

        size = sys.getsizeof(current)
        if is_dataclass(current) and not isinstance(current, type):
            return size + sum(estimate(getattr(current, field.name)) for field in fields(current))
        if isinstance(current, dict):
            return size + sum(estimate(key) + estimate(item) for key, item in current.items())
        if isinstance(current, (list, tuple, set, frozenset)):
            return size + sum(estimate(item) for item in current)
        return size

    return estimate(value)


def estimate_cache_entry_bytes(key, value):
    """Include the key and an allowance for the OrderedDict node and entry metadata."""
    return _ENTRY_OVERHEAD_BYTES + estimate_retained_bytes(key) + estimate_retained_bytes(value)


@dataclass(frozen=True)
class _CacheEntry:
    value: object
    expires_at: float
    size_bytes: int


class ByteBoundedTTLCache:
    """Thread-safe TTL cache with true access-order LRU and an estimated byte budget."""

    def __init__(
        self,
        max_bytes,
        max_entries,
        *,
        clock=time.monotonic,
        size_of=estimate_cache_entry_bytes,
        clone=copy.copy,
    ):
        if max_bytes < 0:
            raise ValueError("max_bytes must be non-negative")
        if max_entries < 0:
            raise ValueError("max_entries must be non-negative")
        self.max_bytes = max_bytes
        self.max_entries = max_entries
        self._clock = clock
        self._size_of = size_of
        self._clone = clone
        self._entries = OrderedDict()
        self._total_bytes = 0
        self._lock = threading.Lock()

    def get(self, key):
        now = self._clock()
        with self._lock:
            self._remove_expired(now)
            entry = self._entries.get(key)
            if entry is None:
                return None
            self._entries.move_to_end(key)
            value = entry.value
        return self._clone(value)

    def set(self, key, value, ttl):
        if ttl <= 0 or self.max_bytes == 0 or self.max_entries == 0:
            now = self._clock()
            with self._lock:
                self._remove_expired(now)
                self._remove(key)
            return False

        stored_value = self._clone(value)
        size_bytes = max(0, int(self._size_of(key, stored_value)))
        now = self._clock()

        with self._lock:
            self._remove_expired(now)
            self._remove(key)
            if size_bytes > self.max_bytes:
                return False

            self._entries[key] = _CacheEntry(
                value=stored_value,
                expires_at=now + ttl,
                size_bytes=size_bytes,
            )
            self._total_bytes += size_bytes
            self._evict_to_budget()
            return key in self._entries

    def contains(self, key):
        now = self._clock()
        with self._lock:
            self._remove_expired(now)
            return key in self._entries

    def clear(self):
        with self._lock:
            self._entries.clear()
            self._total_bytes = 0

    @property
    def total_bytes(self):
        with self._lock:
            return self._total_bytes

    @property
    def keys_lru_to_mru(self):
        now = self._clock()
        with self._lock:
            self._remove_expired(now)
            return tuple(self._entries.keys())

    def __len__(self):
        now = self._clock()
        with self._lock:
            self._remove_expired(now)
            return len(self._entries)

    def _remove_expired(self, now):
        expired = [key for key, entry in self._entries.items() if entry.expires_at <= now]
        for key in expired:
            self._remove(key)

    def _remove(self, key):
        entry = self._entries.pop(key, None)
        if entry is not None:
            self._total_bytes -= entry.size_bytes

    def _evict_to_budget(self):
        while len(self._entries) > self.max_entries or self._total_bytes > self.max_bytes:
            _key, entry = self._entries.popitem(last=False)
            self._total_bytes -= entry.size_bytes
