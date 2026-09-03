// pages/knowledge/knowledge.js
// 知识库管理：上传文档（wx.uploadFile multipart）/ 文件列表 / 按文件删除（二次确认）
// 列表按文件粒度（对齐夏令营项目模式）：每个文件独立删除，不再整库笼统删除
// 后端 /api/rag/*：userId 从登录态取，JWT 由 request.js 自动注入（上传走 wx.uploadFile 单独带 header）
const { get, delete: del } = require('../../utils/request')

Page({
  data: {
    files: [],
    loading: false,
    uploading: false,
    uploadProgress: 0,      // 0-100：HTTP 上传阶段实时进度
    uploadStatus: ''        // ''=隐藏 / 上传中 / 正在向量化入库…（服务器处理阶段，上传已 100%）
  },

  onShow() {
    const app = getApp()
    if (!app.globalData.token) {
      wx.switchTab({ url: '/pages/profile/profile' })
      return
    }
    this.loadFiles()
  },

  // MCP 透传文本：每行 "filename | N 块 | 时间"，解析成文件列表
  loadFiles() {
    const app = getApp()
    this.setData({ loading: true })
    get('/api/rag/files', { userId: app.globalData.userId })
      .then(data => {
        const lines = String(data || '').split('\n').map(s => s.trim()).filter(Boolean)
        const files = lines.map(line => {
          const parts = line.split('|').map(s => s.trim())
          return {
            name: parts[0] || line,
            count: parts[1] || '',
            time: parts[2] || ''
          }
        })
        this.setData({ files, loading: false })
      })
      .catch(e => {
        this.setData({ loading: false })
        wx.showToast({ title: (e && e.msg) || '加载失败', icon: 'none' })
      })
  },

  // 选文件（聊天记录里的文件）→ 上传到默认知识库
  chooseUpload() {
    const app = getApp()
    const that = this
    wx.chooseMessageFile({
      count: 1,
      type: 'file',
      success(res) {
        const file = res.tempFiles[0]
        if (!file) return
        if (file.size > 50 * 1024 * 1024) {
          wx.showToast({ title: '文件超过 50MB 限制', icon: 'none' })
          return
        }
        that.setData({ uploading: true, uploadProgress: 0, uploadStatus: '上传中' })
        wx.uploadFile({
          url: app.globalData.baseUrl + '/api/rag/upload',
          filePath: file.path,
          name: 'file',
          formData: { userId: app.globalData.userId },
          header: { 'Authorization': 'Bearer ' + app.globalData.token },
          onProgressUpdate(res) {
            // HTTP 上传阶段实时进度；到 100% 后服务器还要解析+向量化（慢在这），切状态文案
            if (res.progress) {
              that.setData({
                uploadProgress: res.progress,
                uploadStatus: res.progress >= 100 ? '正在向量化入库…' : '上传中'
              })
            }
          },
          success(res) {
            try {
              const j = JSON.parse(res.data)
              if (j.code === 0) {
                wx.showToast({ title: j.data || '上传成功', icon: 'success' })
                that.loadFiles()
              } else {
                wx.showToast({ title: j.msg || '上传失败', icon: 'none' })
              }
            } catch (e) {
              wx.showToast({ title: '上传失败', icon: 'none' })
            }
          },
          fail() {
            wx.showToast({ title: '网络异常，请重试', icon: 'none' })
          },
          complete() {
            that.setData({ uploading: false, uploadProgress: 0, uploadStatus: '' })
          }
        })
      }
    })
  },

  // 按文件删除（二次确认）
  onDel(e) {
    const file = e.currentTarget.dataset
    wx.showModal({
      title: '删除文件',
      content: '确认删除「' + file.name + '」？该文件的全部内容将被清除，不可恢复。',
      confirmColor: '#e54d42',
      success: res => {
        if (res.confirm) this.doDelete(file.name)
      }
    })
  },

  doDelete(name) {
    const app = getApp()
    del('/api/rag/delete-file', { userId: app.globalData.userId, filename: name })
      .then(data => {
        wx.showToast({ title: data || '已删除', icon: 'success' })
        this.loadFiles()
      })
      .catch(e => wx.showToast({ title: (e && e.msg) || '删除失败', icon: 'none' }))
  }
})