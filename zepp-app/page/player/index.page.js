import * as hmUI from '@zos/ui'
import { showToast } from '@zos/interaction'
import { px } from '@zos/utils'
import { BasePage } from '@zeppos/zml/base-page'
import {
  DEVICE_WIDTH,
  COLORS,
  saveLastStatus,
  sendCommand,
  isActive,
  isPaused,
  describeStatus,
  statusColor,
  parseParams
} from '../../utils/common'

const REFRESH_MS = 3000
const MARGIN = px(12)
const GAP = px(8)
const FULL_W = DEVICE_WIDTH - 2 * MARGIN

const ROW_TIME = px(172)
const ROW_MAIN = px(252)
const ROW_VOL = px(342)

function button(x, y, w, h, text, onClick, opts) {
  const o = opts || {}
  return hmUI.createWidget(hmUI.widget.BUTTON, {
    x,
    y,
    w,
    h,
    radius: px(20),
    text,
    text_size: o.size || px(28),
    color: COLORS.text,
    normal_color: o.primary ? COLORS.accentDark : COLORS.button,
    press_color: o.primary ? COLORS.accentPress : COLORS.buttonPress,
    click_func: onClick
  })
}

// Reparte el ancho en n botones iguales
function columns(n) {
  const w = Math.floor((FULL_W - (n - 1) * GAP) / n)
  const xs = []
  for (let i = 0; i < n; i++) xs.push(MARGIN + i * (w + GAP))
  return { w, xs }
}

Page(
  BasePage({
    state: {
      stationId: null,
      stationName: '',
      playOnOpen: false,
      status: null,
      busy: false,
      timer: null,
      nameText: null,
      stateText: null,
      titleText: null,
      toggleButton: null
    },

    onInit(params) {
      const p = parseParams(params)
      this.state.stationId = p.id || null
      this.state.stationName = p.name || ''
      this.state.playOnOpen = !!p.play
    },

    build() {
      this.state.nameText = hmUI.createWidget(hmUI.widget.TEXT, {
        x: MARGIN,
        y: px(18),
        w: FULL_W,
        h: px(58),
        text: this.state.stationName || 'Radio',
        text_size: px(34),
        color: COLORS.text,
        align_h: hmUI.align.CENTER_H,
        align_v: hmUI.align.CENTER_V,
        text_style: hmUI.text_style.ELLIPSIS
      })

      this.state.stateText = hmUI.createWidget(hmUI.widget.TEXT, {
        x: MARGIN,
        y: px(78),
        w: FULL_W,
        h: px(34),
        text: this.state.playOnOpen ? 'Conectando con la emisora...' : 'Consultando...',
        text_size: px(24),
        color: COLORS.muted,
        align_h: hmUI.align.CENTER_H,
        align_v: hmUI.align.CENTER_V,
        text_style: hmUI.text_style.ELLIPSIS
      })

      this.state.titleText = hmUI.createWidget(hmUI.widget.TEXT, {
        x: MARGIN,
        y: px(112),
        w: FULL_W,
        h: px(54),
        text: '',
        text_size: px(22),
        color: 0xc8c8c8,
        align_h: hmUI.align.CENTER_H,
        align_v: hmUI.align.CENTER_V,
        text_style: hmUI.text_style.WRAP
      })

      // Fila 1: moverse en el tiempo
      const t = columns(4)
      button(t.xs[0], ROW_TIME, t.w, px(70), '-5m', () => this.seek(-300), { size: px(26) })
      button(t.xs[1], ROW_TIME, t.w, px(70), '-30s', () => this.seek(-30), { size: px(26) })
      button(t.xs[2], ROW_TIME, t.w, px(70), '+30s', () => this.seek(30), { size: px(26) })
      button(t.xs[3], ROW_TIME, t.w, px(70), 'Dir.', () => this.live(), { size: px(26) })

      // Fila 2: emisora anterior / pausa / siguiente
      const smallW = px(80)
      const midW = Math.floor(FULL_W - 2 * smallW - 2 * GAP)
      button(MARGIN, ROW_MAIN, smallW, px(80), '<', () => this.command('PREV'))
      this.state.toggleButton = button(
        MARGIN + smallW + GAP,
        ROW_MAIN,
        midW,
        px(80),
        'Pausa',
        () => this.toggle(),
        { primary: true, size: px(30) }
      )
      button(MARGIN + smallW + GAP + midW + GAP, ROW_MAIN, smallW, px(80), '>', () => this.command('NEXT'))

      // Fila 3: volumen del móvil y apagar
      const v = columns(3)
      button(v.xs[0], ROW_VOL, v.w, px(70), 'Vol -', () => this.volume('down'))
      button(v.xs[1], ROW_VOL, v.w, px(70), 'Apagar', () => this.command('STOP'), { size: px(24) })
      button(v.xs[2], ROW_VOL, v.w, px(70), 'Vol +', () => this.volume('up'))

      if (this.state.playOnOpen && this.state.stationId) {
        this.command('PLAY', { id: this.state.stationId })
      } else {
        this.refresh()
      }
      this.state.timer = setInterval(() => this.refresh(), REFRESH_MS)
    },

    onDestroy() {
      if (this.state.timer) {
        clearInterval(this.state.timer)
        this.state.timer = null
      }
    },

    refresh() {
      if (this.state.busy) return
      sendCommand(this, 'STATUS').then((status) => this.render(status))
    },

    command(method, params, after) {
      if (this.state.busy) return
      this.state.busy = true
      sendCommand(this, method, params).then((status) => {
        this.state.busy = false
        if (status && status.ok === false) {
          showToast({ content: status.error || 'No se pudo enviar la orden' })
        } else if (after) {
          after(status)
        }
        this.render(status)
      })
    },

    toggle() {
      const status = this.state.status
      if (isActive(status)) {
        this.command('TOGGLE')
      } else {
        const id = (status && status.stationId) || this.state.stationId
        this.command('PLAY', id ? { id } : {})
      }
    },

    canSeek() {
      const status = this.state.status
      if (status && status.ok && isActive(status) && status.canSeek) return true
      showToast({
        content: isActive(status) ? 'Esta emisora no permite retroceder' : 'Primero pon una emisora'
      })
      return false
    },

    seek(seconds) {
      if (this.canSeek()) this.command('SEEK', { by: seconds })
    },

    live() {
      if (this.canSeek()) this.command('LIVE')
    },

    volume(dir) {
      this.command('VOLUME', { dir }, (status) => {
        if (status && typeof status.volume === 'number') {
          showToast({ content: 'Volumen ' + status.volume + '/' + status.maxVolume })
        }
      })
    },

    render(status) {
      this.state.status = status
      if (status && status.ok) {
        saveLastStatus(status)
        if (status.stationId) this.state.stationId = status.stationId
        if (status.stationName) this.state.stationName = status.stationName
      }

      this.state.nameText.setProperty(hmUI.prop.MORE, {
        text: this.state.stationName || 'Radio'
      })
      this.state.stateText.setProperty(hmUI.prop.MORE, {
        text: describeStatus(status),
        color: statusColor(status)
      })
      this.state.titleText.setProperty(hmUI.prop.MORE, {
        text: (status && status.ok && isActive(status) && status.title) || ''
      })

      const smallW = px(80)
      this.state.toggleButton.setProperty(hmUI.prop.MORE, {
        x: MARGIN + smallW + GAP,
        y: ROW_MAIN,
        w: Math.floor(FULL_W - 2 * smallW - 2 * GAP),
        h: px(80),
        text: !isActive(status) ? 'Sonar' : isPaused(status) ? 'Seguir' : 'Pausa'
      })
    }
  })
)
