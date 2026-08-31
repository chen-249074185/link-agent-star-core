// utils/request.js
// 通用请求封装：baseUrl + JWT token 注入 + 统一响应 {code,msg,data} 处理
const app = getApp()

function request(method, path, data) {
  return new Promise((resolve, reject) => {
    wx.request({
      url: app.globalData.baseUrl + path,
      method: method,
      data: data,
      header: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + (app.globalData.token || '')
      },
      success: res => {
        if (res.statusCode === 401 || res.statusCode === 403) {
          reject({ code: res.statusCode, msg: '请先登录' })
          return
        }
        const j = res.data || {}
        if (j.code === 0) {
          resolve(j.data)
        } else {
          reject(j)
        }
      },
      fail: err => reject({ code: -1, msg: '网络请求失败' })
    })
  })
}

module.exports = {
  get: (path, data) => request('GET', path, data),
  post: (path, data) => request('POST', path, data),
  delete: (path, data) => request('DELETE', path, data)
}