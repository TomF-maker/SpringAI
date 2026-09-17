// pages/login/login.js - 登录页
// 后端约定：
// - POST /api/auth/login 成功 → data.token
// - 异地登录 → data.requirePhoneVerify=true 且 data.token=null
// - 密码错误 → HTTP 401（不是 200）
// 不判 requirePhoneVerify 就把 undefined 存成 token，会变成"登录失败表现得像登录成功"。
const api = require('../../utils/api');
const auth = require('../../utils/auth');

Page({
  data: {
    username: '',
    password: '',
    error: '',
    loading: false
  },

  onUsernameInput(e) { this.setData({ username: e.detail.value, error: '' }); },
  onPasswordInput(e) { this.setData({ password: e.detail.value, error: '' }); },

  async doLogin() {
    const { username, password } = this.data;
    if (!username || !password) return;
    this.setData({ loading: true, error: '' });
    try {
      // 登录接口不走统一信封解包的 success 判定 —— 我们要看 data 里的字段。
      // 用 request 直接拿原始 envelope。
      const envelope = await api.request({
        url: '/api/auth/login',
        method: 'POST',
        skipAuth: true,                  // 登录前没有 token
        data: { username, password }
      });
      // 401 已经在 api.js 里被拦掉了，到这里 success 一定是 true。
      const data = envelope || {};
      if (data.requirePhoneVerify) {
        // 小程序端第一阶段不做 SMS 流程，给一句清楚的指引。
        const masked = data.maskedPhone ? '（尾号 ' + data.maskedPhone + '）' : '';
        this.setData({
          error: '检测到本次登录来自新的网络环境，需要手机号' + masked + '验证。请先在网页端完成验证后再来。'
        });
        return;
      }
      if (!data.token) {
        this.setData({ error: '登录失败：服务未返回 token' });
        return;
      }
      // 存登录态、回到问答页（reLaunch 把栈清空，避免登录页留在栈底）
      auth.save(data.token, {
        username: data.username,
        realName: data.realName,
        userId: data.userId
      });
      getApp().globalData.logged = true;
      getApp().globalData.userInfo = auth.getUserInfo();
      wx.reLaunch({ url: '/pages/chat/chat' });
    } catch (err) {
      // 401 / 业务失败都落在这里
      const msg = (err && err.errMessage) || '登录失败，请重试';
      this.setData({ error: msg });
    } finally {
      this.setData({ loading: false });
    }
  },

  goChatAsGuest() {
    wx.reLaunch({ url: '/pages/chat/chat' });
  }
});
