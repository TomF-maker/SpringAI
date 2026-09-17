// app.js - 采购智能助手小程序入口
// 维护全局登录态、未读提示等。真正的 token 校验在 utils/auth.js。
const auth = require('./utils/auth');

App({
  globalData: {
    // 后端 API 根地址。**上线前必须改为已备案的 HTTPS 域名**。
    // 本地调试可在微信开发者工具勾选「不校验合法域名」。
    apiBase: 'http://124.221.251.183:8080',
    userInfo: null,
    // 是否已登录。页面通过 auth.isLoggedIn() 判定，不直接读这个字段。
    logged: false
  },

  onLaunch() {
    // 启动时把本地存储里的登录态读回来，避免每次冷启动都要求重新登录。
    const restored = auth.restore();
    this.globalData.logged = restored;
    this.globalData.userInfo = restored ? auth.getUserInfo() : null;
  },

  /** 退出登录：清本地态、回到登录页。 */
  logout() {
    auth.clear();
    this.globalData.logged = false;
    this.globalData.userInfo = null;
    wx.reLaunch({ url: '/pages/login/login' });
  }
});
