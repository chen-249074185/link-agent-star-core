// pages/index/index.js
// 会话模式与 Web 一致（30 分钟滑动窗口）：sessionId 保持并回传——30 分钟内消息
// 自动归入同一会话（滑动计时），隔 30 分钟自动开新会话；「新对话」= 强制开新
const app = getApp()

Page({
  data: {
    messages: [],
    input: '',
    sending: false,
    thinking: false,      // 助手"正在思考"三点动画气泡
    focused: false,       // 输入框聚焦（卡片高亮）
    scrollInto: '',
    sessionId: '',        // 当前会话（后端返回，滑动窗口沿用）
    forceNew: false       // 点了"新对话"：下一条消息强制开新会话
  },

  onShow() {
    // 未登录跳"我的"（tabBar 页用 wx.switchTab）
    if (!app.globalData.token) {
      wx.switchTab({ url: '/pages/profile/profile' })
    }
  },

  onInput(e) { this.setData({ input: e.detail.value }) },

  onFocus() { this.setData({ focused: true }) },
  onBlur() { this.setData({ focused: false }) },

  // 快捷示例：点欢迎语示例直接发送
  onQuick() {
    this.setData({ input: '在北京租房，预算6000，通勤30分钟内' })
    this.onSend()
  },

  // 新对话：清空本地消息，下一条强制开新会话（与 Web 端「新对话」同语义）
  onNewChat() {
    this.setData({ messages: [], sessionId: '', forceNew: true, input: '' })
  },

  now() {
    const d = new Date()
    return d.getHours().toString().padStart(2, '0') + ':' + d.getMinutes().toString().padStart(2, '0')
  },

  onSend() {
    const text = (this.data.input || '').trim()
    console.log('[chat] onSend text=', text, 'sending=', this.data.sending, 'token=', !!app.globalData.token)
    if (!text || this.data.sending) return
    if (!app.globalData.token) {
      wx.switchTab({ url: '/pages/profile/profile' })
      return
    }

    const messages = this.data.messages.concat([{ role: 'user', content: text, time: this.now() }])
    const forceNew = this.data.forceNew
    this.setData({ messages, input: '', sending: true, thinking: true, scrollInto: 'bottom', forceNew: false })
    console.log('[chat] 发送后 messages 数量=', messages.length)
    this.streamChat(text, this.data.sessionId, forceNew)
  },

  // 对话：小程序模拟器/真机拿不到 SSE 流式响应（微信网络层不转发流式 body）→
  // 用后端非流式端点 /api/chat/ask（一次性返回完整回复，内部走工具循环+记忆双写）
  // sessionId 回传保持滑动窗口会话；newSession=1 强制开新
  streamChat(text, sessionId, forceNew) {
    const that = this

    wx.request({
      url: app.globalData.baseUrl + '/api/chat/ask',
      method: 'GET',
      data: {
        message: text,
        token: app.globalData.token,
        sessionId: sessionId || undefined,
        newSession: forceNew ? 1 : 0
      },
      success(res) {
        console.log('[chat] ask 请求完成 statusCode=', res.statusCode, 'body=', JSON.stringify(res.data).slice(0, 200))
        if (res.statusCode === 401) {
          that.setData({ sending: false, thinking: false })
          that.pushAssistant('登录已过期，请到"我的"重新登录')
          wx.switchTab({ url: '/pages/profile/profile' })
          return
        }
        const j = res.data || {}
        if (j.code === 0 && j.data && j.data.reply) {
          // 保存后端会话归属（滑动窗口沿用同一 sessionId；超时后端自动开新并回传新 id）
          if (j.data.sessionId) {
            that.setData({ sessionId: j.data.sessionId })
          }
          that.setData({ sending: false, thinking: false })
          that.appendAssistant(j.data.reply)
          console.log('[chat] 回复后 messages 数量=', that.data.messages.length, '最后一条=', JSON.stringify(that.data.messages[that.data.messages.length - 1]).slice(0, 100))
        } else {
          that.setData({ sending: false, thinking: false })
          that.pushAssistant((j.msg) || '请求失败，请重试')
        }
      },
      fail(err) {
        console.error('[chat] ask 请求失败', err)
        that.setData({ sending: false, thinking: false })
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
    const messages = this.data.messages.concat([{ role: 'assistant', content, time: this.now() }])
    this.setData({ messages, scrollInto: 'bottom' })
  }
})