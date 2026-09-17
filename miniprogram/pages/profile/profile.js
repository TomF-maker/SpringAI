// pages/profile/profile.js - 个人中心
const api = require('../../utils/api');
const auth = require('../../utils/auth');
const { fmtTime } = require('../../utils/util');

Page({
  data: {
    loading: true,
    user: {},
    avatarText: 'U',
    isAdmin: false,
    points: 0,
    membership: { active: false },
    freeLimit: 0,
    freeRemaining: 0,
    lastLoginText: ''
  },

  onLoad() {
    if (!auth.guard()) return;
    this.load();
  },

  onShow() {
    if (auth.isLoggedIn()) this.load();
  },

  async load() {
    this.setData({ loading: true });
    try {
      const me = await api.get('/api/users/me');
      const userInfo = auth.getUserInfo() || {};
      // 头像用真实姓名或用户名的第一个字
      const nameForAvatar = (me.realName || me.username || userInfo.username || '用户').trim();
      const avatarText = nameForAvatar ? nameForAvatar.charAt(0).toUpperCase() : 'U';
      this.setData({
        user: me,
        avatarText,
        isAdmin: !!me.isAdmin,
        points: me.points || 0,
        lastLoginText: fmtTime(me.lastLoginTime)
      });
      // 会员态与免费额度从 /api/rag/chat/quota 拿，避免单独维护会员接口
      try {
        const q = await api.get('/api/rag/chat/quota');
        const member = !!q.member;
        this.setData({
          membership: {
            active: member,
            planName: me.memberType || '',
            permanent: me.memberType === 'PERMANENT',
            expireText: fmtTime(me.memberExpireAt)
          },
          freeLimit: q.limit || 0,
          freeRemaining: q.remaining == null ? 0 : q.remaining
        });
      } catch (e) { /* 不阻塞页面渲染 */ }
    } catch (err) {
      api.toastError(err);
    } finally {
      this.setData({ loading: false });
    }
  },

  goDocuments() { wx.navigateTo({ url: '/pages/documents/documents' }); },
  goDashboard() { wx.navigateTo({ url: '/pages/dashboard/dashboard' }); },
  goLogin() { wx.reLaunch({ url: '/pages/login/login' }); },

  callAdmin() {
    wx.showModal({
      title: '联系管理员',
      content: '如需开通会员、修改部门或重置账号，请联系企业管理员。',
      showCancel: false,
      confirmText: '知道了'
    });
  },

  doLogout() {
    wx.showModal({
      title: '退出登录',
      content: '确定要退出当前账号吗？',
      confirmText: '退出',
      confirmColor: '#c33d3d',
      success: (res) => {
        if (!res.confirm) return;
        getApp().logout();
      }
    });
  }
});
