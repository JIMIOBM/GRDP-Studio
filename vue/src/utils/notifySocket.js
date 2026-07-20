let socket = null
let reconnectTimer = null
let reconnectEnabled = false

const socketUrl = () => {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/docker-api/common/notify`
}

const scheduleReconnect = () => {
  if (!reconnectEnabled || reconnectTimer) return
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null
    connectNotifySocket()
  }, 3000)
}

export function connectNotifySocket() {
  if (typeof window === 'undefined') return null
  reconnectEnabled = true

  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return socket
  }

  socket = new WebSocket(socketUrl())
  socket.addEventListener('message', (event) => {
    let message = event.data
    try {
      message = JSON.parse(event.data)
    } catch {
      // Plain-text notifications are forwarded unchanged.
    }
    window.dispatchEvent(new CustomEvent('notify-message', { detail: message }))
  })
  socket.addEventListener('close', () => {
    socket = null
    scheduleReconnect()
  })
  socket.addEventListener('error', () => socket?.close())
  return socket
}

export function disconnectNotifySocket() {
  reconnectEnabled = false
  if (reconnectTimer) {
    window.clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  socket?.close()
  socket = null
}

export function initNotifySocket() {
  if (typeof window !== 'undefined' && window.localStorage.getItem('account')) {
    connectNotifySocket()
  }
}
