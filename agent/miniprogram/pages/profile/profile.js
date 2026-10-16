// pages/profile/profile.js
const { get, post, put, delete: del } = require('../../utils/request')

Page({
  data: {
    logged: false,
    username: '',
    userId: '',
    avatar: '星',
    password: '',
    loading: false,
    err: '',
    lpConfigured: false,    // 猎聘 Token 是否已配置
    lpUpdatedAt: '',
    lpToken: '',
    lpEditing: false,       // 输入框展开
    lpSaving: false,
    lpErr: '',
    presets: [],            // 内置人设预设（快捷项）
    personaConfigured: false,
    personaText: '',        // 当前人设（展示）
    personaDraft: '',       // 编辑草稿
    personaEditing: false,
    personaErr: ''
  },

  onShow() {
    // 每次进页面刷新登录态（storage 可能已由别处更新）
    const app = getApp()
    if (app.globalData.token) {
      this.setData({
        logged: true,
        username: wx.getStorageSync('username') || '',
        userId: app.globalData.userId,
        avatar: (wx.getStorageSync('username') || '星').slice(0, 1).toUpperCase()
      })
      this.loadLpStatus()
      this.loadPersona()
      this.loadPresets()
    } else {
      this.setData({ logged: false })
    }
  },

  // 猎聘 Token 配置状态（不返回明文）
  loadLpStatus() {
    const app = getApp()
    get('/api/config/liepin-token', { userId: app.globalData.userId })
      .then(d => this.setData({ lpConfigured: !!d.configured, lpUpdatedAt: d.updatedAt || '' }))
      .catch(() => {})
  },

  onLpToken(e) { this.setData({ lpToken: e.detail.value }) },

  toggleLpEdit() {
    this.setData({ lpEditing: !this.data.lpEditing, lpErr: '' })
  },

  saveLpToken() {
    const t = (this.data.lpToken || '').trim()
    if (!t) {
      this.setData({ lpErr: '请输入猎聘 Token' })
      return
    }
    const app = getApp()
    this.setData({ lpSaving: true })
    post('/api/config/liepin-token', { userId: app.globalData.userId, token: t }, { query: true })
      .then(() => {
        this.setData({ lpConfigured: true, lpToken: '', lpEditing: false, lpErr: '', lpSaving: false })
        wx.showToast({ title: '已保存', icon: 'success' })
      })
      .catch(e => {
        this.setData({ lpSaving: false, lpErr: (e && e.msg) || '保存失败' })
      })
  },

  removeLpToken() {
    wx.showModal({
      title: '删除猎聘配置',
      content: '删除后猎聘岗位/简历功能不可用，需重新配置 Token。',
      confirmColor: '#e54d42',
      success: res => {
        if (!res.confirm) return
        const app = getApp()
        del('/api/config/liepin-token', { userId: app.globalData.userId })
          .then(() => {
            this.setData({ lpConfigured: false, lpUpdatedAt: '', lpToken: '', lpEditing: false })
            wx.showToast({ title: '已删除', icon: 'success' })
          })
          .catch(e => wx.showToast({ title: (e && e.msg) || '删除失败', icon: 'none' }))
      }
    })
  },

  // ===== 助手人设：预设快捷 + 自定义自由文本，持久化 users.persona =====
  loadPersona() {
    const app = getApp()
    get('/api/user/persona', { userId: app.globalData.userId })
      .then(d => {
        this.setData({
          personaConfigured: !!d.configured,
          personaText: d.persona || '',
          personaDraft: d.persona || ''
        })
      })
      .catch(() => {})
  },

  loadPresets() {
    const app = getApp()
    get('/api/personas', { userId: app.globalData.userId })
      .then(list => this.setData({ presets: list || [] }))
      .catch(() => this.setData({ presets: [] }))
  },

  togglePersonaEdit() {
    this.setData({
      personaEditing: !this.data.personaEditing,
      personaDraft: this.data.personaText,   // 打开时回填当前人设
      personaErr: ''
    })
  },

  onPersonaInput(e) { this.setData({ personaDraft: e.detail.value }) },

  // 预设快捷项：点一下填入草稿（自由文本仍可改）
  pickPreset(e) {
    const id = e.currentTarget.dataset.id
    const p = (this.data.presets || []).find(x => x.id === id)
    if (p) this.setData({ personaDraft: p.name })
  },

  savePersona() {
    const t = (this.data.personaDraft || '').trim()
    if (!t) {
      this.setData({ personaErr: '请输入人设内容' })
      return
    }
    const app = getApp()
    put('/api/user/persona', { userId: app.globalData.userId, persona: t })
      .then(d => {
        this.setData({ personaConfigured: true, personaText: t, personaEditing: false, personaErr: '' })
        wx.showToast({ title: '人设已生效', icon: 'success' })
      })
      .catch(e => this.setData({ personaErr: (e && e.msg) || '保存失败' }))
  },

  clearPersona() {
    const app = getApp()
    del('/api/user/persona', { userId: app.globalData.userId })
      .then(() => {
        this.setData({ personaConfigured: false, personaText: '', personaDraft: '', personaEditing: false })
        wx.showToast({ title: '已恢复默认', icon: 'success' })
      })
      .catch(e => wx.showToast({ title: (e && e.msg) || '清除失败', icon: 'none' }))
  },

  onUsername(e) { this.setData({ username: e.detail.value }) },
  onPassword(e) { this.setData({ password: e.detail.value }) },

  onLogin() {
    const { username, password } = this.data
    if (!username || !password) {
      this.setData({ err: '请输入用户名和密码' })
      return
    }
    this.setData({ loading: true, err: '' })
    post('/api/user/login', { type: 'username', username, password })
      .then(data => {
        const app = getApp()
        app.globalData.token = data.token
        app.globalData.userId = data.userId
        wx.setStorageSync('token', data.token)
        wx.setStorageSync('userId', data.userId)
        wx.setStorageSync('username', username)
        this.setData({ logged: true, userId: data.userId, username, avatar: username.slice(0, 1).toUpperCase(), loading: false })
        wx.showToast({ title: '登录成功', icon: 'success' })
      })
      .catch(e => {
        this.setData({ loading: false, err: (e && e.msg) || '登录失败' })
      })
  },

  onLogout() {
    const app = getApp()
    app.globalData.token = ''
    app.globalData.userId = ''
    wx.removeStorageSync('token')
    wx.removeStorageSync('userId')
    wx.removeStorageSync('username')
    this.setData({ logged: false })
    wx.showToast({ title: '已退出', icon: 'none' })
  }
})