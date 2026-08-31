// pages/index/index.js
const app = getApp()

Page({
  data: {
    messages: [],
    input: '',
    sending: false,
    scrollInto: ''
  },

  onShow() {
    // 未登录跳"我的"（tabBar 页用 wx.switchTab）
    if (!app.globalData.token) {
      wx.switchTab({ url: '/pages/profile/profile' })
    }
  },

  onInput(e) { this.setData({ input: e.detail.value }) },

  onSend() {
    const text = (this.data.input || '').trim()
    console.log('[chat] onSend text=', text, 'sending=', this.data.sending, 'token=', !!app.globalData.token)
    if (!text || this.data.sending) return
    if (!app.globalData.token) {
      wx.switchTab({ url: '/pages/profile/profile' })
      return
    }

    const messages = this.data.messages.concat([{ role: 'user', content: text }])
    this.setData({ messages, input: '', sending: true })
    console.log('[chat] 发送后 messages 数量=', messages.length)
    this.streamChat(text)
  },

  // 对话：小程序模拟器/真机拿不到 SSE 流式响应（微信网络层不转发流式 body）→
  // 用后端非流式端点 /api/chat/ask（一次性返回完整回复，内部走工具循环+记忆双写）
  streamChat(text) {
    const that = this

    wx.request({
      url: app.globalData.baseUrl + '/api/chat/ask',
      method: 'GET',
      data: {
        message: text,
        token: app.globalData.token
      },
      success(res) {
        console.log('[chat] ask 请求完成 statusCode=', res.statusCode, 'body=', JSON.stringify(res.data).slice(0, 200))
        if (res.statusCode === 401) {
          that.setData({ sending: false })
          that.pushAssistant('登录已过期，请到"我的"重新登录')
          wx.switchTab({ url: '/pages/profile/profile' })
          return
        }
        const j = res.data || {}
        if (j.code === 0 && j.data && j.data.reply) {
          that.appendAssistant(j.data.reply)
          that.setData({ sending: false })
          console.log('[chat] 回复后 messages 数量=', that.data.messages.length, '最后一条=', JSON.stringify(that.data.messages[that.data.messages.length - 1]).slice(0, 100))
        } else {
          that.setData({ sending: false })
          that.pushAssistant((j.msg) || '请求失败，请重试')
        }
      },
      fail(err) {
        console.error('[chat] ask 请求失败', err)
        that.setData({ sending: false })
        that.pushAssistant('请求失败，请重试')
      }
    })
  },

  appendAssistant(token) {
    const messages = this.data.messages
    const last = messages[messages.length - 1]
    if (last && last.role === 'assistant') {
      last.content += token
      this.setData({ messages, scrollInto: 'bottom' })
    } else {
      this.pushAssistant(token)
    }
  },

  pushAssistant(content) {
    const messages = this.data.messages.concat([{ role: 'assistant', content }])
    this.setData({ messages, scrollInto: 'bottom' })
  }
})