// utils/config.js - 集中管理 API 根地址
// 上线前在这里改一处即可，无需全局搜索替换。
function apiBase() {
  const app = getApp();
  return (app && app.globalData && app.globalData.apiBase) || 'http://124.221.251.183:8080';
}

module.exports = { apiBase };
