// utils/util.js - 时间格式化等小工具
function pad(n) { return n < 10 ? '0' + n : '' + n; }

/** 把后端的 LocalDateTime 数组或 ISO 字符串格式化成 'YYYY-MM-DD HH:mm'。
 *  小程序端不依赖 moment —— 一个能覆盖后端当前两种序列化形态的简单函数就够。 */
function fmtTime(input) {
  if (!input) return '';
  // 数组形态：[2026, 9, 17, 10, 30, 0]
  if (Array.isArray(input) && input.length >= 5) {
    const [y, m, d, h, mi] = input;
    return pad(y) + '-' + pad(m) + '-' + pad(d) + ' ' + pad(h) + ':' + pad(mi);
  }
  if (typeof input === 'string') {
    // ISO: 2026-09-17T10:30:00 或带 Z
    const s = input.replace('T', ' ').replace(/\.\d+.*$/, '');
    return s.length >= 16 ? s.slice(0, 16) : s;
  }
  return String(input);
}

/** 截断长字符串，保留首尾。 */
function truncate(s, n) {
  if (!s) return '';
  if (s.length <= n) return s;
  return s.slice(0, n - 1) + '…';
}

module.exports = { pad, fmtTime, truncate };
