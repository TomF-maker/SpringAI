// utils/auth.js - 登录态持久化与访问守卫
// token 存在 storage 里（key 见下方常量），与网页端 localStorage 同名同义，
// 但小程序与网页 storage 是两套，互不通用。
const STORAGE_KEY = 'pa_token';
const USER_KEY = 'pa_user';

/** 读取 token，不读 userInfo。 */
function getToken() {
  try {
    return wx.getStorageSync(STORAGE_KEY) || '';
  } catch (e) {
    return '';
  }
}

/** 读取缓存的用户信息（登录时一并存入）。 */
function getUserInfo() {
  try {
    const s = wx.getStorageSync(USER_KEY);
    return s ? (typeof s === 'string' ? JSON.parse(s) : s) : null;
  } catch (e) {
    return null;
  }
}

/** 是否已登录。页面 onLoad 时用它判定要不要跳登录页。 */
function isLoggedIn() {
  return !!getToken();
}

/** 写入登录态（登录成功后调用）。 */
function save(token, user) {
  wx.setStorageSync(STORAGE_KEY, token);
  if (user) {
    wx.setStorageSync(USER_KEY, typeof user === 'string' ? user : JSON.stringify(user));
  }
}

/** 清空登录态。 */
function clear() {
  try {
    wx.removeStorageSync(STORAGE_KEY);
    wx.removeStorageSync(USER_KEY);
  } catch (e) {}
}

/** 把本地态读回来。供 App.onLaunch 调用。 */
function restore() {
  return isLoggedIn();
}

/** 守卫：未登录就跳到登录页。返回 true 表示已登录、可继续。 */
function guard(redirect) {
  if (isLoggedIn()) return true;
  if (redirect !== false) {
    wx.reLaunch({ url: '/pages/login/login' });
  }
  return false;
}

/** 组装带 Authorization 的请求头。 */
function authHeader(extra) {
  const headers = Object.assign({ 'Content-Type': 'application/json' }, extra || {});
  const t = getToken();
  if (t) headers['Authorization'] = 'Bearer ' + t;
  return headers;
}

module.exports = {
  getToken,
  getUserInfo,
  isLoggedIn,
  save,
  clear,
  restore,
  guard,
  authHeader
};
