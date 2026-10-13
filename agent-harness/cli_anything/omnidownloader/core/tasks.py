import time
from urllib.parse import quote

from cli_anything.omnidownloader.utils.omnidownloader_backend import BackendError


def task_path(task_id):
    return "/api/tasks/" + quote(task_id, safe="")


def get_task(backend, task_id):
    for task in backend.request("GET", "/api/tasks"):
        if task.get("id") == task_id:
            return task
    raise BackendError(f"Task not found: {task_id}")


def wait_task(backend, task_id, timeout, interval):
    deadline = time.monotonic() + timeout
    while True:
        task = get_task(backend, task_id)
        if task["status"] == "COMPLETED":
            return task
        if task["status"] in ("FAILED", "CANCELLED"):
            raise BackendError(f"Task {task_id} {task['status']}: {task.get('errorMessage', '')}")
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise BackendError(f"Timed out waiting for {task_id}; task remains on the server")
        time.sleep(min(interval, remaining))
