// utils/request.js
// 通用请求封装：baseUrl + JWT token 注入 + 统一响应 {code,msg,data} 处理
// 参数传递：GET/PUT/DELETE 走 query string（后端 @RequestParam 只认 query）；POST 走 JSON body
const app = getApp()

function toQuery(data) {
  if (!data) return ''
  const qs = Object.keys(data)
    .map(k => encodeURIComponent(k) + '=' + encodeURIComponent(data[k]))
    .join('&')
  return qs ? '?' + qs : ''
}

function request(method, path, data, opts) {
  opts = opts || {}
  const useQuery = method === 'GET' || method === 'PUT' || method === 'DELETE' || opts.query === true
  return new Promise((resolve, reject) => {
    const url = useQuery
      ? app.globalData.baseUrl + path + toQuery(data)
      : app.globalData.baseUrl + path
    wx.request({
      url: url,
      method: method,
      data: useQuery ? undefined : data,
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
  post: (path, data, opts) => request('POST', path, data, opts),
  put: (path, data) => request('PUT', path, data),
  delete: (path, data) => request('DELETE', path, data)
}