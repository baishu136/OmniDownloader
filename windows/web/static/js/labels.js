import { t } from './i18n.js';
/* 状态徽章与下载类型文案映射（对齐 TaskCard.kt & AppStrings.kt） */

export const STATUS = {
  PENDING: { label: '等待中', pill: '', progress: '' },
  DOWNLOADING: { label: '正在下载', pill: 'omni-pill--primary', progress: '' },
  PROCESSING: { label: '正在合并', pill: 'omni-pill--primary', progress: '' },
  COMPLETED: { label: '已完成', pill: 'omni-pill--success', progress: 'omni-progress__bar--success' },
  FAILED: { label: '失败', pill: 'omni-pill--error', progress: 'omni-progress__bar--error' },
  CANCELLED: { label: '已取消', pill: 'omni-pill--warning', progress: 'omni-progress__bar--warning' },
  WAITING_FOR_AUTH: { label: '需重新验证', pill: 'omni-pill--warning', progress: 'omni-progress__bar--warning' },
};

export function statusInfo(status) {
  const row = STATUS[status];
  if (row) return { ...row, label: t(row.label) };
  return { label: status ? String(status) : t('未知'), pill: '', progress: '' };
}

export const TYPE_LABELS = {
  VIDEO_WITH_AUDIO: '视频',
  VIDEO_ONLY: '纯画面',
  AUDIO_ONLY: '音频',
  GIF: 'GIF',
  COVER: '封面',
  IMAGE: '原图',
  IMAGE_ZIP: '导出 ZIP',
};

export function typeLabel(type) {
  return t(TYPE_LABELS[type] || type || '');
}

export const FILTERS = {
  all: () => true,
  active: (task) => ['DOWNLOADING', 'PROCESSING', 'PENDING'].includes(task.status),
  done: (task) => task.status === 'COMPLETED',
};
