"""Persist link preparation while using the existing download queue."""
import asyncio
import json
import re
import uuid
from pathlib import Path
from core.config import CONFIG_FILE
from core.session_store import protect


def links(text):
    if len(text) > 100_000:
        raise ValueError('每批链接文本最多 100000 字符')
    found = re.findall(r'https?://[^\s<>"]+|\bBV[A-Za-z0-9]{10}\b', text, re.I)
    result = list(dict.fromkeys(value.rstrip("。），！；,;)]}'") for value in found))
    if not result or len(result) > 100:
        raise ValueError('请添加 1 至 100 条链接')
    return result


class BatchManager:
    def __init__(self):
        self.path = CONFIG_FILE.parent / 'batches.bin'
        self.jobs = {}
        self.workers = {}
        if self.path.exists():
            self.jobs = json.loads(protect(self.path.read_bytes(), decrypt=True))
            for job in self.jobs.values():
                for row in job['items']:
                    if row['status'] in ('PENDING', 'RESOLVING'):
                        row.update(status='CANCELLED', note='服务重启，请重试未提交的链接')

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix('.tmp')
        temporary.write_bytes(protect(json.dumps(self.jobs, ensure_ascii=False).encode()))
        temporary.replace(self.path)

    def create(self, text, options, enqueue):
        urls = links(text)
        if sum(key in self.workers for key in self.jobs) >= 4:
            raise ValueError('最多同时处理 4 个批次')
        if len(self.jobs) >= 20:
            retired = next((key for key in self.jobs if key not in self.workers), None)
            if retired is None:
                raise ValueError('请等待已有批次完成')
            self.jobs.pop(retired)
        job = {'id': uuid.uuid4().hex, 'options': options, 'items': [
            {'id': uuid.uuid4().hex, 'url': url, 'status': 'PENDING', 'note': '', 'taskIds': []} for url in urls]}
        self.jobs[job['id']] = job
        self.save()
        self.run(job['id'], enqueue)
        return job

    def run(self, identifier, enqueue):
        if identifier in self.workers:
            return
        job = self.jobs[identifier]
        for row in job['items']:
            if row['status'] in ('FAILED', 'CANCELLED'):
                row.update(status='PENDING', note='')

        async def work():
            try:
                for row in job['items']:
                    if row['status'] != 'PENDING':
                        continue
                    row.update(status='RESOLVING', note='正在解析')
                    self.save()
                    try:
                        row['taskIds'] = await asyncio.wait_for(enqueue(row, job['options']), timeout=150)
                        row.update(status='QUEUED', note='已加入下载队列')
                    except asyncio.CancelledError:
                        row.update(status='CANCELLED', note='已停止添加下载')
                        raise
                    except Exception as error:
                        from engine.media_errors import sanitize
                        row.update(status='FAILED', note=sanitize(getattr(error, 'detail', str(error)))[:400])
                    finally:
                        self.save()
            finally:
                self.workers.pop(identifier, None)
                self.save()

        self.workers[identifier] = asyncio.create_task(work())

    def cancel(self, identifier):
        for row in self.jobs[identifier]['items']:
            if row['status'] == 'PENDING':
                row.update(status='CANCELLED', note='已停止添加下载')
        worker = self.workers.get(identifier)
        if worker:
            worker.cancel()
        self.save()
