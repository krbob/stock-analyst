import copy
import threading
from dataclasses import dataclass, field


def _clone_exception(error):
    """Clone an exception without re-running a subclass constructor with incompatible args."""
    try:
        cloned = error.__class__.__new__(error.__class__)
        BaseException.__init__(cloned, *error.args)
        if hasattr(error, "__dict__"):
            cloned.__dict__.update(error.__dict__)
        return cloned
    except Exception:
        try:
            return copy.copy(error)
        except Exception:
            return error


@dataclass
class _Flight:
    owner_thread_id: int
    completed: threading.Event = field(default_factory=threading.Event)
    participants: int = 1
    result: object = None
    error: BaseException | None = None


class SingleFlight:
    """Coalesce overlapping synchronous calls by key without holding a lock during the load."""

    def __init__(self, *, clone_result=copy.copy, clone_error=_clone_exception):
        self._clone_result = clone_result
        self._clone_error = clone_error
        self._condition = threading.Condition()
        self._flights = {}

    def call(self, key, loader):
        thread_id = threading.get_ident()
        with self._condition:
            flight = self._flights.get(key)
            if flight is None:
                flight = _Flight(owner_thread_id=thread_id)
                self._flights[key] = flight
                leader = True
            else:
                if flight.owner_thread_id == thread_id:
                    raise RuntimeError(f"Recursive single-flight call for key: {key}")
                flight.participants += 1
                leader = False
            self._condition.notify_all()

        if leader:
            return self._run_leader(key, flight, loader)
        return self._wait_for_leader(flight)

    def _run_leader(self, key, flight, loader):
        try:
            result = loader()
        except BaseException as error:
            self._complete(key, flight, error=error)
            raise
        else:
            self._complete(key, flight, result=result)
            return result

    def _wait_for_leader(self, flight):
        flight.completed.wait()
        if flight.error is not None:
            try:
                error = self._clone_error(flight.error)
            except Exception:
                error = flight.error
            raise error
        return self._clone_result(flight.result)

    def _complete(self, key, flight, *, result=None, error=None):
        with self._condition:
            flight.result = result
            flight.error = error
            current = self._flights.get(key)
            if current is flight:
                self._flights.pop(key)
            flight.completed.set()
            self._condition.notify_all()

    @property
    def active_count(self):
        with self._condition:
            return len(self._flights)

    def wait_for_participants(self, key, count, timeout):
        """Wait for test/diagnostic purposes until a flight has the requested participants."""
        with self._condition:
            return self._condition.wait_for(
                lambda: (
                    (flight := self._flights.get(key)) is not None
                    and flight.participants >= count
                ),
                timeout=timeout,
            )
