// pages/documents/documents.js - 文档管理（管理员）
const api = require('../../utils/api');
const auth = require('../../utils/auth');
const { fmtTime, truncate } = require('../../utils/util');

const PAGE_SIZE = 10;
// 与网页端 documents.html 的状态映射对齐
const STATUS_TEXT = { 0: '待处理', 1: '已处理', 2: '失败' };
const STATUS_BADGE = { 0: 'pa-badge-warning', 1: 'pa-badge-success', 2: 'pa-badge-danger' };

Page({
  data: {
    list: [],
    total: 0,
    page: 1,
    loading: false,
    hasMore: true,
    uploading: null   // { name, progress }
  },

  onLoad() {
    if (!auth.guard()) return;
    // 权限提示：普通用户不应进到这页
    const me = auth.getUserInfo() || {};
    // 不阻断，让后端用 403 兜底；这只是一个更友好的前置提示
    this.loadList();
  },

  onPullDownRefresh() {
    this.setData({ page: 1, hasMore: true, list: [] });
    this.loadList().finally(() => wx.stopPullDownRefresh());
  },

  onReachBottom() {
    if (!this.data.hasMore || this.data.loading) return;
    this.loadList();
  },

  async loadList() {
    this.setData({ loading: true });
    try {
      const page = await api.get('/api/documents/list', {
        page: this.data.page,
        size: PAGE_SIZE
      });
      const records = (page && page.records) || [];
      const total = (page && page.total) || 0;
      const norm = records.map(d => {
        const ext = (d.fileName || d.title || '').split('.').pop().toLowerCase();
        return {
          id: d.id,
          title: d.title || d.fileName || '未命名文档',
          statusText: STATUS_TEXT[d.status] || '未知',
          statusBadge: STATUS_BADGE[d.status] || '',
          sizeText: humanSize(d.fileSize),
          timeText: fmtTime(d.createdAt),
          icon: iconForExt(ext)
        };
      });
      this.setData({
        list: this.data.list.concat(norm),
        total,
        page: this.data.page + 1,
        hasMore: this.data.list.length + norm.length < total
      });
    } catch (err) {
      api.toastError(err);
    } finally {
      this.setData({ loading: false });
    }
  },

  onPickFile() {
    // 小程序文件选择：从会话文件中选（wx.chooseMessageFile），支持任意类型
    wx.chooseMessageFile({
      count: 1,
      type: 'file',
      success: (res) => {
        const file = res.tempFiles && res.tempFiles[0];
        if (!file) return;
        if (file.size > 50 * 1024 * 1024) {
          wx.showToast({ title: '文件不能超过 50MB', icon: 'none' });
          return;
        }
        this.upload(file);
      },
      fail: () => {}
    });
  },

  async upload(file) {
    // departmentId 是必填项，默认用当前用户所属部门；没有的话给 0 让后端兜底
    const me = auth.getUserInfo() || {};
    const departmentId = me.departmentId || 0;
    this.setData({ uploading: { name: file.name, progress: 0 } });

    try {
      const task = wx.uploadFile({
        url: getApp().globalData.apiBase + '/api/documents/upload/metadata',
        filePath: file.path,
        name: 'file',
        formData: {
          title: file.name,
          departmentId: String(departmentId),
          visibleType: '1',
          isPublic: 'false'
        },
        header: auth.authHeader(),
        success: (res) => {
          this.setData({ uploading: null });
          let body = {};
          try { body = JSON.parse(res.data || '{}'); } catch (e) {}
          if (res.statusCode >= 200 && res.statusCode < 300 && body.success !== false) {
            wx.showToast({ title: '上传成功', icon: 'success' });
            this.setData({ page: 1, hasMore: true, list: [] });
            this.loadList();
          } else if (res.statusCode === 401) {
            auth.clear();
            wx.reLaunch({ url: '/pages/login/login' });
          } else {
            wx.showToast({ title: body.errMessage || '上传失败', icon: 'none' });
          }
        },
        fail: () => {
          this.setData({ uploading: null });
          wx.showToast({ title: '上传失败，请检查网络', icon: 'none' });
        }
      });
      task.onProgressUpdate((p) => {
        this.setData({ 'uploading.progress': p.progress });
      });
    } catch (e) {
      this.setData({ uploading: null });
      wx.showToast({ title: '上传异常', icon: 'none' });
    }
  },

  async onDownload(e) {
    const id = e.currentTarget.dataset.id;
    wx.showLoading({ title: '准备下载…', mask: true });
    try {
      const tempPath = await api.downloadFile('/api/documents/download/' + id);
      wx.hideLoading();
      // 打开文档：依赖文件类型，小程序会用合适的预览器
      wx.openDocument({
        filePath: tempPath,
        showMenu: true,
        fail: () => {
          wx.showToast({ title: '已下载到：' + tempPath, icon: 'none', duration: 3000 });
        }
      });
    } catch (err) {
      wx.hideLoading();
      api.toastError(err);
    }
  },

  onDelete(e) {
    const id = e.currentTarget.dataset.id;
    const title = e.currentTarget.dataset.title || '';
    wx.showModal({
      title: '删除文档',
      content: '删除后文档及其向量数据将一并清除，不可恢复。确认删除「' + title + '」？',
      confirmText: '删除',
      confirmColor: '#c33d3d',
      success: (res) => {
        if (!res.confirm) return;
        api.del('/api/documents/' + id)
          .then(() => {
            wx.showToast({ title: '已删除', icon: 'success' });
            // 简化处理：直接重新加载第一页
            this.setData({ page: 1, hasMore: true, list: [] });
            this.loadList();
          })
          .catch(err => api.toastError(err));
      }
    });
  },

  onOpenDoc(e) {
    // 点击卡片本身 = 下载（与网页端点击标题类似）
    this.onDownload(e);
  }
});

function humanSize(bytes) {
  if (!bytes) return '-';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

function iconForExt(ext) {
  const map = { pdf: '📕', doc: '📘', docx: '📘', txt: '📝', md: '📝', xlsx: '📗', xls: '📗' };
  return map[ext] || '📄';
}
