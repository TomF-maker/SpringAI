// pages/dashboard/dashboard.js - 数据看板（管理员）
// 小程序端不引 ECharts —— 卡片 + 条形对比更适合手机竖屏。
const api = require('../../utils/api');
const auth = require('../../utils/auth');

Page({
  data: {
    loading: true,
    error: '',
    stats: {
      totalDocuments: 0,
      processedDocuments: 0,
      pendingDocuments: 0,
      publicDocuments: 0,
      totalUsers: 0,
      totalDepartments: 0
    },
    qStats: {
      unavailable: false,
      totalQuestions: 0,
      todayQuestions: 0,
      totalConversations: 0,
      activeUsers: 0,
      avgQuestionsPerUser: 0,
      hitRate: 0,
      avgLatencyText: '-',
      localHitCount: 0,
      docAnswerCount: 0,
      kbGapCount: 0,
      toolCallCount: 0,
      errorCount: 0
    },
    hitTypes: [],
    qDays: 7
  },

  onLoad() {
    if (!auth.guard()) return;
    this.load();
  },

  async load() {
    this.setData({ loading: true, error: '' });
    try {
      await Promise.all([ this.loadStats(), this.loadQuestion(7) ]);
    } catch (e) {
      this.setData({ error: (e && e.errMessage) || '加载失败' });
    } finally {
      this.setData({ loading: false });
    }
  },

  async loadStats() {
    try {
      const s = await api.get('/api/dashboard/statistics');
      this.setData({
        stats: {
          totalDocuments: s.totalDocuments || 0,
          processedDocuments: s.processedDocuments || 0,
          pendingDocuments: s.pendingDocuments || 0,
          publicDocuments: s.publicDocuments || 0,
          totalUsers: s.totalUsers || 0,
          totalDepartments: s.totalDepartments || 0
        }
      });
    } catch (e) { /* 局部失败不阻断整体加载 */ }
  },

  async loadQuestion(days) {
    try {
      const q = await api.get('/api/dashboard/question-statistics', { days });
      const hitTypes = this.buildHitTypes(q);
      this.setData({
        qStats: {
          unavailable: !!q.unavailable,
          totalQuestions: q.totalQuestions || 0,
          todayQuestions: q.todayQuestions || 0,
          totalConversations: q.totalConversations || 0,
          activeUsers: q.activeUsers || 0,
          avgQuestionsPerUser: (q.avgQuestionsPerUser || 0).toFixed(1),
          hitRate: (q.hitRate || 0).toFixed(1),
          avgLatencyText: formatLatency(q.avgLatencyMs),
          localHitCount: q.localHitCount || 0,
          docAnswerCount: q.docAnswerCount || 0,
          kbGapCount: q.kbGapCount || 0,
          toolCallCount: q.toolCallCount || 0,
          errorCount: q.errorCount || 0
        },
        hitTypes
      });
    } catch (e) { /* 表未建等场景，让 unavailable 字段保持 false 不渲染条形图 */ }
  },

  buildHitTypes(q) {
    const items = [
      { label: '文档问答', value: q.docAnswerCount || 0, cls: 'fill-doc' },
      { label: '本地知识库', value: q.localHitCount || 0, cls: 'fill-local' },
      { label: '工具调用', value: q.toolCallCount || 0, cls: 'fill-tool' },
      { label: '知识库缺口', value: q.kbGapCount || 0, cls: 'fill-gap' },
      { label: '处理异常', value: q.errorCount || 0, cls: 'fill-error' }
    ];
    const max = Math.max(1, ...items.map(i => i.value));
    return items.map(i => Object.assign({}, i, { pct: Math.round(i.value / max * 100) }));
  },

  switchRange(e) {
    const days = parseInt(e.currentTarget.dataset.days, 10);
    if (days === this.data.qDays) return;
    this.setData({ qDays: days });
    this.loadQuestion(days);
  }
});

function formatLatency(ms) {
  if (!ms || ms <= 0) return '-';
  if (ms < 1000) return ms + ' ms';
  return (ms / 1000).toFixed(1) + ' s';
}
