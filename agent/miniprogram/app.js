// app.js
App({
  onLaunch() {
    // 恢复登录态（wx.setStorageSync 存过 token/userId）
    const token = wx.getStorageSync('token')
    const userId = wx.getStorageSync('userId')
    if (token) {
      this.globalData.token = token
      this.globalData.userId = userId || ''
    }
  },
  globalData: {
    token: '',
    userId: '',
    // 后端地址：模拟器可用 http://localhost:8080；真机预览必须用电脑局域网 IP（手机和电脑同一 Wi-Fi）
    baseUrl: 'http://localhost:8080'
  }
})