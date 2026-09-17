// pages/chat/chat.js - 智能问答页
// 走非流式 GET /api/rag/chat。会话 id 由服务端在 data.conversationId 返回，
// 下一问把它带回去 —— 不带的话每次都另起新会话，历史里就散成一堆。
const api = require('../../utils/api');
const auth = require('../../utils/auth');
const { fmtTime } = require('../../utils/util');

let msgIdSeq = 0;

Page({
  data: {
    draft: '',
    messages: [],
    thinking: false,
    conversationId: null,
    scrollAnchor: 'msg-bottom',
    quota: { show: false, anonymous: false, member: false, limit: 0, remaining: 0, warning: false }
  },

  onLoad() {
    if (auth.isLoggedIn()) {
      this.loadQuota();
    } else {
      // 游客：显示 IP 限额横幅。后端会按网段判定，超限就回 429。
      this.loadQuota();
    }
  },

  onShow() {
    // 从登录页回来时刷新一下配额态（游客 → 会员）
    this.loadQuota();
  },

  async loadQuota() {
    try {
      const q = await api.get('/api/rag/chat/quota');
      const limit = q.limit || 0;
      const remaining = q.remaining == null ? limit : q.remaining;
      const warning = q.enabled && remaining <= 3;
      this.setData({
        quota: {
          show: true,
          anonymous: !!q.anonymous,
          member: !!q.member,
          limit,
          remaining,
          warning
        }
      });
    } catch (e) {
      // 拿不到配额信息不阻塞聊天 —— 服务端仍会在超限时拦截。
      this.setData({ quota: { show: false, anonymous: !auth.isLoggedIn(), member: false, limit: 0, remaining: 0, warning: false } });
    }
  },

  onInput(e) { this.setData({ draft: e.detail.value }); },

  useSuggest(e) {
    const q = e.currentTarget.dataset.q;
    this.setData({ draft: q });
  },

  async onSend() {
    if (this.data.thinking) {
      // 第二阶段：取消按钮 —— 真正调用 RequestTask.abort() 中断请求
      // 非流式接口拿到 abort 后服务端那一侧的 RAG 不会再把答案写回，
      // 但前端至少能立刻把界面从"思考中"解锁，不再让用户干等。
      if (this._task && typeof this._task.abort === 'function') {
        try { this._task.abort(); } catch (e) { /* 已结束的请求 abort 会抛，忽略 */ }
      }
      this._task = null;
      this.setData({ thinking: false });
      // 追加一条系统消息提示用户这次提问已取消
      const cancelMsg = {
        id: ++msgIdSeq,
        role: 'system',
        text: '（已取消）',
        time: fmtTime(new Date().toISOString())
      };
      this.setData({
        messages: this.data.messages.concat([cancelMsg]),
        scrollAnchor: 'msg-bottom'
      });
      return;
    }
    const question = (this.data.draft || '').trim();
    if (!question) return;

    // 1. 立刻把用户消息上屏
    const userMsg = {
      id: ++msgIdSeq,
      role: 'user',
      text: question,
      time: fmtTime(new Date().toISOString())
    };
    this.setData({
      messages: this.data.messages.concat([userMsg]),
      draft: '',
      thinking: true,
      scrollAnchor: 'msg-bottom'
    });

    try {
      const data = await api.get('/api/rag/chat', {
        question,
        conversationId: this.data.conversationId || undefined
      }, {
        // 把 wx.request 返回的 RequestTask 交出来，供取消按钮 abort
        onTask: (t) => { this._task = t; }
      });
      this._task = null;
      // data.answer / data.conversationId
      if (data && data.conversationId) {
        this.setData({ conversationId: data.conversationId });
      }
      // 答案来源标注：把 score 转成百分比字符串，WXML 不好做乘法运算
      const sources = (data && data.sources) || null;
      if (sources && sources.length) {
        sources.forEach(s => {
          if (s.score != null) {
            s.scoreText = (s.score * 100).toFixed(0) + '%';
          }
        });
      }
      const aiMsg = {
        id: ++msgIdSeq,
        role: 'ai',
        text: (data && data.answer) || '（未收到回答）',
        sources,
        srcExpanded: false,
        time: fmtTime(new Date().toISOString())
      };
      this.setData({
        messages: this.data.messages.concat([aiMsg]),
        thinking: false,
        scrollAnchor: 'msg-bottom'
      });
      // 提问消耗了配额，刷新横幅
      this.loadQuota();
    } catch (err) {
      this._task = null;
      this.setData({ thinking: false });
      const code = err && err.errCode;
      // 用户主动取消 —— 不要弹"提问失败"toast，系统消息已经给过反馈了
      if (code === 'ABORTED') return;
      let msg = (err && err.errMessage) || '提问失败，请重试';
      // 429：今日额度用完
      if (code === '429' || code === 429) {
        msg = auth.isLoggedIn()
          ? '今日免费提问额度已用完，升级会员即可不限次数'
          : '今日游客提问额度已用完，登录后可获得更多次数';
      }
      wx.showToast({ title: msg, icon: 'none', duration: 2500 });
    }
  },

  // 展开/折叠某条 AI 回答的来源标注
  toggleSources(e) {
    const id = e.currentTarget.dataset.id;
    const messages = this.data.messages.map(m =>
      m.id === id ? { ...m, srcExpanded: !m.srcExpanded } : m
    );
    this.setData({ messages });
  },

  goLogin() {
    wx.navigateTo({ url: '/pages/login/login' });
  }
});
