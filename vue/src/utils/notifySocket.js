const RECONNECT_DELAY = 3000

let socket = null
let reconnectTimer = null
let manuallyClosed = false

const getAccount = () => {
  try {
    const accountText = localStorage.getItem('account')
    return accountText ? JSON.parse(accountText) : null
  } catch {
    return null
  }
}

const buildNotifyUrl = () => {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return new URL('/docker-api/common/notify', `${protocol}//${window.location.host}`).toString()
}

const clearReconnectTimer = () => {
  if (!reconnectTimer) return
  window.clearTimeout(reconnectTimer)
  reconnectTimer = null
}

const scheduleReconnect = () => {
  clearReconnectTimer()
  if (manuallyClosed || !getAccount()) return

  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null
    connectNotifySocket()
  }, RECONNECT_DELAY)
}

const parseMessage = (data) => {
  if (typeof data !== 'string') return data
  try {
    return JSON.parse(data)
  } catch {
    return data
  }
}

export const disconnectNotifySocket = () => {
  manuallyClosed = true
  clearReconnectTimer()

  if (!socket) return
  const currentSocket = socket
  socket = null
  currentSocket.close()
}

export const connectNotifySocket = () => {
  if (!getAccount()) return null
  if (socket && [WebSocket.OPEN, WebSocket.CONNECTING].includes(socket.readyState)) return socket

  manuallyClosed = false
  clearReconnectTimer()

  socket = new WebSocket(buildNotifyUrl())

  socket.addEventListener('message', (event) => {
    const message = parseMessage(event.data)

    if (message?.type === 'ping' && socket?.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: 'pong', payload: null }))
      return
    }

    window.dispatchEvent(new CustomEvent('notify-message', { detail: message }))
  })

  socket.addEventListener('close', () => {
    socket = null
    scheduleReconnect()
  })

  socket.addEventListener('error', () => {
    socket?.close()
  })

  return socket
}

export const initNotifySocket = () => {
  if (getAccount()) connectNotifySocket()

  window.addEventListener('beforeunload', disconnectNotifySocket)
}
