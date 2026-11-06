// Servicio complementario: corre dentro de la app Zepp del móvil.
// Recibe las órdenes del reloj por Bluetooth y se las pasa por HTTP a la
// app Android "Radio Reloj", que escucha solo en el propio móvil (127.0.0.1).
import { BaseSideService } from '@zeppos/zml/base-side'

const PHONE_APP = 'http://127.0.0.1:8765'

// Orden del reloj -> ruta de la app Android
const ROUTES = {
  PING: '/api/ping',
  GET_STATIONS: '/api/stations',
  STATUS: '/api/status',
  PLAY: '/api/play',
  STOP: '/api/stop',
  PAUSE: '/api/pause',
  RESUME: '/api/resume',
  TOGGLE: '/api/toggle',
  SEEK: '/api/seek',
  LIVE: '/api/live',
  NEXT: '/api/next',
  PREV: '/api/prev',
  VOLUME: '/api/volume'
}

function buildQuery(params) {
  const parts = []
  if (params && typeof params === 'object') {
    Object.keys(params).forEach((key) => {
      const value = params[key]
      if (value !== undefined && value !== null && value !== '') {
        parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(value)))
      }
    })
  }
  return parts.length ? '?' + parts.join('&') : ''
}

function parseBody(body) {
  if (body && typeof body === 'object') return body
  if (typeof body === 'string' && body.length) {
    try {
      return JSON.parse(body)
    } catch (e) {
      return null
    }
  }
  return null
}

async function callPhoneApp(path, params) {
  const url = PHONE_APP + path + buildQuery(params)
  try {
    const response = await fetch({ url, method: 'GET', timeout: 6000 })
    const body = parseBody(response && response.body)
    if (!body) {
      return { ok: false, error: 'Respuesta vacía de Radio Reloj' }
    }
    return body
  } catch (error) {
    console.log('Radio Reloj no responde en ' + url + ': ' + error)
    return {
      ok: false,
      offline: true,
      error: 'Abre Radio Reloj en el móvil'
    }
  }
}

AppSideService(
  BaseSideService({
    onInit() {},

    onRequest(req, res) {
      const path = ROUTES[req.method]
      if (!path) {
        res(null, { ok: false, error: 'Orden desconocida: ' + req.method })
        return
      }
      callPhoneApp(path, req.params).then((result) => res(null, result))
    },

    onRun() {},

    onDestroy() {}
  })
)
