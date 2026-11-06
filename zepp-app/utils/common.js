import { getDeviceInfo } from '@zos/device'
import { LocalStorage } from '@zos/storage'

export const { width: DEVICE_WIDTH, height: DEVICE_HEIGHT } = getDeviceInfo()

export const COLORS = {
  text: 0xffffff,
  muted: 0x9a9a9a,
  accent: 0x2ecc8f,
  accentDark: 0x16533c,
  accentPress: 0x0f3a2a,
  item: 0x262626,
  itemPress: 0x3a3a3a,
  button: 0x333333,
  buttonPress: 0x555555,
  error: 0xff6b5b,
  warning: 0xffc857
}

const storage = new LocalStorage()
const STATIONS_KEY = 'stations_v1'
const LAST_STATUS_KEY = 'last_status_v1'

export function loadCachedStations() {
  try {
    const raw = storage.getItem(STATIONS_KEY, '')
    const list = raw ? JSON.parse(raw) : []
    return Array.isArray(list) ? list : []
  } catch (e) {
    return []
  }
}

export function saveCachedStations(list) {
  try {
    storage.setItem(STATIONS_KEY, JSON.stringify(list || []))
  } catch (e) {}
}

export function loadLastStatus() {
  try {
    const raw = storage.getItem(LAST_STATUS_KEY, '')
    return raw ? JSON.parse(raw) : null
  } catch (e) {
    return null
  }
}

export function saveLastStatus(status) {
  try {
    storage.setItem(LAST_STATUS_KEY, JSON.stringify(status || {}))
  } catch (e) {}
}

// Envía una orden al móvil a través del servicio complementario.
// Nunca rechaza: si algo falla devuelve { ok: false, error }.
export function sendCommand(page, method, params) {
  return page
    .request({ method, params: params || {} }, { timeout: 12000 })
    .then((result) => result || { ok: false, error: 'Sin respuesta del móvil' })
    .catch(() => ({
      ok: false,
      offline: true,
      error: 'Reloj sin conexión con el móvil'
    }))
}

export function isActive(status) {
  return (
    !!status &&
    (status.state === 'playing' || status.state === 'buffering' || status.state === 'paused')
  )
}

export function isPaused(status) {
  return !!status && status.state === 'paused'
}

// 75 -> "1:15", 3725 -> "1:02:05"
export function formatDelay(seconds) {
  const s = Math.max(0, Math.floor(seconds || 0))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const pad = (n) => (n < 10 ? '0' + n : '' + n)
  return h > 0 ? h + ':' + pad(m) + ':' + pad(sec) : m + ':' + pad(sec)
}

// Texto corto del estado para la pantalla del reloj
export function describeStatus(status) {
  if (!status) return 'Conectando...'
  if (status.ok === false) return status.error || 'Error'
  const behind = status.behind || 0
  switch (status.state) {
    case 'playing':
      return behind > 0 ? 'Diferido -' + formatDelay(behind) : 'En directo'
    case 'paused':
      return 'En pausa -' + formatDelay(behind)
    case 'buffering':
      return 'Conectando con la emisora...'
    case 'error':
      return status.error || 'No se pudo conectar'
    default:
      return 'Parada'
  }
}

export function statusColor(status) {
  if (!status || status.ok === false || status.state === 'error') return COLORS.error
  if (status.state === 'playing') return COLORS.accent
  if (status.state === 'paused') return COLORS.warning
  return COLORS.muted
}

export function parseParams(params) {
  if (!params) return {}
  if (typeof params === 'object') return params
  try {
    return JSON.parse(params)
  } catch (e) {
    return {}
  }
}
