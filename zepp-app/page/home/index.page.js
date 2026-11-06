import * as hmUI from '@zos/ui'
import { push } from '@zos/router'
import { showToast } from '@zos/interaction'
import { px } from '@zos/utils'
import { BasePage } from '@zeppos/zml/base-page'
import {
  DEVICE_WIDTH,
  DEVICE_HEIGHT,
  COLORS,
  loadCachedStations,
  saveCachedStations,
  saveLastStatus,
  sendCommand,
  isActive,
  describeStatus,
  statusColor
} from '../../utils/common'

const REFRESH_MS = 5000
const LIST_TOP = px(128)
const ITEM_H = px(84)
const LIST_X = px(14)
const LIST_W = DEVICE_WIDTH - px(28)

function typeConfig(count, activeIndex) {
  if (count <= 0) return []
  if (activeIndex < 0 || activeIndex >= count) {
    return [{ start: 0, end: count - 1, type_id: 1 }]
  }
  const cfg = []
  if (activeIndex > 0) cfg.push({ start: 0, end: activeIndex - 1, type_id: 1 })
  cfg.push({ start: activeIndex, end: activeIndex, type_id: 2 })
  if (activeIndex < count - 1) cfg.push({ start: activeIndex + 1, end: count - 1, type_id: 1 })
  return cfg
}

function itemType(typeId, bg, textColor) {
  return {
    type_id: typeId,
    item_height: ITEM_H,
    item_bg_color: bg,
    item_bg_radius: px(20),
    text_view: [
      {
        x: px(22),
        y: px(21),
        w: LIST_W - px(44),
        h: px(42),
        key: 'name',
        color: textColor,
        text_size: px(32),
        align_h: hmUI.align.LEFT,
        text_style: hmUI.text_style.ELLIPSIS
      }
    ],
    text_view_count: 1
  }
}

Page(
  BasePage({
    state: {
      stations: [],
      status: null,
      list: null,
      listSignature: '',
      emptyText: null,
      statusButton: null,
      timer: null
    },

    onInit() {
      this.state.stations = loadCachedStations()
    },

    build() {
      hmUI.createWidget(hmUI.widget.TEXT, {
        x: 0,
        y: px(18),
        w: DEVICE_WIDTH,
        h: px(46),
        text: 'Radio',
        text_size: px(36),
        color: COLORS.text,
        align_h: hmUI.align.CENTER_H,
        align_v: hmUI.align.CENTER_V
      })

      this.state.statusButton = hmUI.createWidget(hmUI.widget.BUTTON, {
        x: LIST_X,
        y: px(68),
        w: LIST_W,
        h: px(50),
        radius: px(25),
        text: 'Conectando...',
        text_size: px(24),
        color: COLORS.muted,
        normal_color: 0x111111,
        press_color: COLORS.buttonPress,
        click_func: () => this.openPlayer()
      })

      this.renderList()
      this.refreshAll()
      this.state.timer = setInterval(() => this.refreshStatus(), REFRESH_MS)
    },

    onDestroy() {
      if (this.state.timer) {
        clearInterval(this.state.timer)
        this.state.timer = null
      }
    },

    refreshAll() {
      sendCommand(this, 'GET_STATIONS').then((res) => {
        if (res && res.ok && Array.isArray(res.stations)) {
          this.state.stations = res.stations.map((s) => ({ id: s.id, name: s.name }))
          saveCachedStations(this.state.stations)
          this.renderList()
        }
        this.refreshStatus()
      })
    },

    refreshStatus() {
      sendCommand(this, 'STATUS').then((status) => this.applyStatus(status))
    },

    applyStatus(status) {
      this.state.status = status
      if (status && status.ok) saveLastStatus(status)

      let text = describeStatus(status)
      if (status && status.ok && isActive(status) && status.stationName) {
        const prefix =
          status.state === 'playing' ? 'Sonando: ' : status.state === 'paused' ? 'En pausa: ' : 'Cargando: '
        text = prefix + status.stationName
      }
      const button = this.state.statusButton
      if (button) {
        button.setProperty(hmUI.prop.MORE, {
          x: LIST_X,
          y: px(68),
          w: LIST_W,
          h: px(50),
          text,
          color: statusColor(status)
        })
      }
      this.renderList()
    },

    activeIndex() {
      const status = this.state.status
      if (!status || !status.ok || !isActive(status)) return -1
      return this.state.stations.findIndex((s) => s.id === status.stationId)
    },

    renderList() {
      const stations = this.state.stations
      const active = this.activeIndex()
      const signature = stations.map((s) => s.id).join('|') + '#' + active

      if (!stations.length) {
        if (!this.state.emptyText) {
          this.state.emptyText = hmUI.createWidget(hmUI.widget.TEXT, {
            x: px(20),
            y: LIST_TOP + px(20),
            w: DEVICE_WIDTH - px(40),
            h: px(200),
            text: 'Sin emisoras todavía.\nAbre Radio Reloj en el móvil.',
            text_size: px(28),
            color: COLORS.muted,
            align_h: hmUI.align.CENTER_H,
            text_style: hmUI.text_style.WRAP
          })
        }
        return
      }
      if (this.state.emptyText) {
        this.state.emptyText.setProperty(hmUI.prop.VISIBLE, false)
      }
      if (signature === this.state.listSignature && this.state.list) return
      this.state.listSignature = signature

      const dataArray = stations.map((s) => ({ name: s.name }))
      const cfg = typeConfig(dataArray.length, active)

      if (this.state.list) {
        this.state.list.setProperty(hmUI.prop.UPDATE_DATA, {
          data_array: dataArray,
          data_count: dataArray.length,
          data_type_config: cfg,
          data_type_config_count: cfg.length,
          on_page: 1
        })
      } else {
        this.state.list = hmUI.createWidget(hmUI.widget.SCROLL_LIST, {
          x: LIST_X,
          y: LIST_TOP,
          w: LIST_W,
          h: DEVICE_HEIGHT - LIST_TOP,
          item_space: px(10),
          item_config: [
            itemType(1, COLORS.item, COLORS.text),
            itemType(2, COLORS.accentDark, COLORS.accent)
          ],
          item_config_count: 2,
          data_array: dataArray,
          data_count: dataArray.length,
          data_type_config: cfg,
          data_type_config_count: cfg.length,
          on_page: 1,
          item_click_func: (list, index) => this.onStationTap(index)
        })
      }
    },

    onStationTap(index) {
      const station = this.state.stations[index]
      if (!station) return
      push({
        url: 'page/player/index.page',
        params: JSON.stringify({ id: station.id, name: station.name, play: true })
      })
    },

    openPlayer() {
      const status = this.state.status
      if (status && status.ok && status.stationId) {
        push({
          url: 'page/player/index.page',
          params: JSON.stringify({ id: status.stationId, name: status.stationName, play: false })
        })
      } else if (status && status.ok === false) {
        showToast({ content: status.error || 'Sin conexión' })
        this.refreshAll()
      } else {
        showToast({ content: 'Elige una emisora de la lista' })
      }
    }
  })
)
