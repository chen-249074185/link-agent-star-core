// pages/memory/memory.js
// 记忆管理：会话列表（/api/chat/history）+ 关键词搜索（/api/chat/search）+ 详情 + 删除
// userId 从登录态取，JWT 由 request.js 自动注入
const { get, delete: del } = require('../../utils/request')

Page({
  data: {
    sessions: [],
    loading: false,
    keyword: '',
    viewing: false,      // 详情视图开关（列表 ↔ 详情切换）
    detail: []
  },

  onShow() {
    const app = getApp()
    if (!app.globalData.token) {
      wx.switchTab({ url: '/pages/profile/profile' })
      return
    }
    if (!this.data.viewing) {
      this.loadHistory()
    }
  },

  loadHistory() {
    const app = getApp()
    this.setData({ loading: true })
    get('/api/chat/history', { userId: app.globalData.userId })
      .then(data => {
        this.setData({ sessions: data || [], loading: false })
      })
      .catch(e => {
        this.setData({ loading: false })
        wx.showToast({ title: (e && e.msg) || '加载失败', icon: 'none' })
      })
  },

  onSearch(e) {
    this.setData({ keyword: e.detail.value })
  },

  doSearch() {
    const q = (this.data.keyword || '').trim()
    const app = getApp()
    if (!q) {
      this.loadHistory()
      return
    }
    get('/api/chat/search', { userId: app.globalData.userId, q })
      .then(data => this.setData({ sessions: data || [] }))
      .catch(e => wx.showToast({ title: (e && e.msg) || '搜索失败', icon: 'none' }))
  },

  openDetail(e) {
    const sid = e.currentTarget.dataset.sid
    const app = getApp()
    get('/api/chat/history/' + encodeURIComponent(sid), { userId: app.globalData.userId })
      .then(data => {
        this.setData({ viewing: true, detail: data || [] })
      })
      .catch(err => wx.showToast({ title: (err && err.msg) || '加载失败', icon: 'none' }))
  },

  backToList() {
    this.setData({ viewing: false, detail: [] })
    this.loadHistory()
  },

  onDel(e) {
    const sid = e.currentTarget.dataset.sid
    const app = getApp()
    wx.showModal({
      title: '删除对话',
      content: '确认删除这段对话？删除后不可恢复。',
      confirmColor: '#e54d42',
      success: res => {
        if (!res.confirm) return
        del('/api/chat/history/' + encodeURIComponent(sid), { userId: app.globalData.userId })
          .then(data => {
            wx.showToast({ title: data || '已删除', icon: 'success' })
            this.loadHistory()
          })
          .catch(err => wx.showToast({ title: (err && err.msg) || '删除失败', icon: 'none' }))
      }
    })
  },

  // 清空全部记忆：对话 + 每日摘要 + 瞬时记忆三处全清（二次确认）
  onClearAll() {
    const app = getApp()
    wx.showModal({
      title: '清空全部记忆',
      content: '将删除该账号的全部对话记录、每日摘要与瞬时记忆，不可恢复。',
      confirmColor: '#e54d42',
      success: res => {
        if (!res.confirm) return
        del('/api/chat/clear-all', { userId: app.globalData.userId })
          .then(data => {
            wx.showToast({ title: data || '已清空', icon: 'success' })
            this.setData({ viewing: false, detail: [], keyword: '' })
            this.loadHistory()
          })
          .catch(err => wx.showToast({ title: (err && err.msg) || '清空失败', icon: 'none' }))
      }
    })
  }
})