// pages/history/history.js - 历史会话列表 + 详情
const api = require('../../utils/api');
const auth = require('../../utils/auth');
const { fmtTime } = require('../../utils/util');

Page({
  data: {
    list: [],
    loading: false,
    hasMore: false,
    detail: { show: false, title: '', messages: [] }
  },

  onLoad() {
    if (!auth.guard()) return;
    this.loadList();
  },

  onPullDownRefresh() {
    this.loadList().finally(() => wx.stopPullDownRefresh());
  },

  async loadList() {
    this.setData({ loading: true });
    try {
      const list = await api.get('/api/conversations');
      const norm = (list || []).map(c => ({
        id: c.id,
        title: c.title || '（未命名会话）',
        messageCount: (c.messages || []).length,
        timeText: fmtTime(c.updatedAt || c.createdAt)
      }));
      this.setData({ list: norm, hasMore: false });
    } catch (err) {
      api.toastError(err);
    } finally {
      this.setData({ loading: false });
    }
  },

  async openDetail(e) {
    const id = e.currentTarget.dataset.id;
    try {
      wx.showLoading({ title: '加载中…', mask: true });
      const conv = await api.get('/api/conversations/' + id);
      this.setData({
        detail: {
          show: true,
          title: (conv && conv.title) || '会话详情',
          messages: (conv && conv.messages) || []
        }
      });
    } catch (err) {
      api.toastError(err);
    } finally {
      wx.hideLoading();
    }
  },

  closeDetail() {
    this.setData({ 'detail.show': false });
  },

  onDelete(e) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '删除会话',
      content: '删除后不可恢复，确定继续吗？',
      confirmText: '删除',
      confirmColor: '#c33d3d',
      success: (res) => {
        if (!res.confirm) return;
        api.del('/api/conversations/' + id)
          .then(() => {
            wx.showToast({ title: '已删除', icon: 'success' });
            this.loadList();
          })
          .catch(err => api.toastError(err));
      }
    });
  }
});
