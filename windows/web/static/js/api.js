/* Minimal API client: one place for fetch, JSON and error handling. */
import { t } from './i18n.js';

export class ApiError extends Error {
  constructor(message, status, detail) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.detail = detail;
  }
}

export async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (options.body && typeof options.body === 'string') headers['Content-Type'] = 'application/json';

  let response;
  try {
    response = await fetch(path, { ...options, headers });
  } catch (error) {
    if (error && error.name === 'AbortError') throw error;
    throw new ApiError(t('无法连接到本地服务，请确认 OmniDownloader 正在运行。'), 0, String(error));
  }

  if (!response.ok) {
    let detail = '';
    try {
      const data = await response.json();
      detail = data.detail || data.message || '';
      if (Array.isArray(detail)) detail = detail.map((item) => item.msg || item).join('；');
    } catch (e) { /* non-JSON error body */ }
    const fallback = response.status === 404
      ? t('当前后端版本未提供该接口（HTTP 404）')
      : t('请求失败（HTTP %d）', response.status);
    throw new ApiError(detail || fallback, response.status, detail);
  }

  if (response.status === 204) return null;
  const type = response.headers.get('content-type') || '';
  return type.includes('application/json') ? response.json() : response.text();
}

export const get = (path) => api(path);

export const post = (path, body, options = {}) =>
  api(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body), ...options });

export const del = (path) => api(path, { method: 'DELETE' });
