// pages/profile/profile.js
const { post } = require('../../utils/request')

Page({
  data: {
    logged: false,
    username: '',
    userId: '',
    avatar: '星',
    password: '',
    loading: false,
    err: ''
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
    } else {
      this.setData({ logged: false })
    }
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