<script setup>
import { ref, computed, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { VideoPlay, VideoPause, Loading } from '@element-plus/icons-vue'

/**
 * 语音气泡：点击播放微信语音
 *
 * 微信的语音是 AMR-NB，浏览器原生放不了，所以用 benz-amr-recorder 在浏览器里解码。
 * 这个库压缩后 440KB，所以是点击时才动态 import，不进首屏包。
 *
 * 服务器没装 ffmpeg 也不打算装：那台机器可用内存只剩几十兆且无 swap，
 * 转码进程有把 JVM 挤爆的风险。放浏览器解码正好绕开。
 */
const props = defineProps({
  url: { type: String, default: '' },
  // 微信识别结果。实测恒为空，留着是因为万一哪天微信修了就能直接用上
  recognition: { type: String, default: '' },
})

const amr = ref(null)
const loading = ref(false)
const playing = ref(false)
const duration = ref(0)
const position = ref(0)
const failed = ref(false)

let tick = null

const hasAudio = computed(() => !!props.url)

/** 有识别文字就显示，没有就显示时长 */
const label = computed(() => {
  if (failed.value) return '播放失败'
  if (loading.value) return '加载中'
  if (duration.value > 0) {
    const cur = playing.value ? Math.floor(position.value) : Math.floor(duration.value)
    return `${cur}″`
  }
  return '语音'
})

function stopTick() {
  if (tick) {
    clearInterval(tick)
    tick = null
  }
}

function startTick() {
  stopTick()
  tick = setInterval(() => {
    if (amr.value && playing.value) {
      position.value = amr.value.getCurrentPosition()
    }
  }, 200)
}

/**
 * 首次点击才真正加载解码库并解码
 * 之后的点击复用同一个实例，不重复下载音频
 */
async function ensureLoaded() {
  if (amr.value) return true
  loading.value = true
  failed.value = false
  try {
    const { default: BenzAMRRecorder } = await import('benz-amr-recorder')
    if (!BenzAMRRecorder.isPlaySupported()) {
      ElMessage.error('当前浏览器不支持播放此格式')
      failed.value = true
      return false
    }
    const inst = new BenzAMRRecorder()
    await inst.initWithUrl(props.url)
    inst.onPlay(() => {
      playing.value = true
      startTick()
    })
    inst.onPause(() => {
      playing.value = false
      stopTick()
    })
    inst.onStop(() => {
      playing.value = false
      position.value = 0
      stopTick()
    })
    inst.onEnded(() => {
      playing.value = false
      position.value = 0
      stopTick()
    })
    duration.value = inst.getDuration() || 0
    amr.value = inst
    return true
  } catch (e) {
    // 取不到音频或解码失败。MinIO 未配置时 url 就是空的，走不到这儿；
    // 到这儿一般是对象被删了、跨域没放开、或者格式不是 AMR-NB
    console.error('[VoiceBubble] 解码失败', e)
    failed.value = true
    ElMessage.error('语音加载失败')
    return false
  } finally {
    loading.value = false
  }
}

async function toggle() {
  if (!hasAudio.value || loading.value) return
  const ok = await ensureLoaded()
  if (!ok || !amr.value) return
  amr.value.playOrPauseOrResume()
}

onBeforeUnmount(() => {
  stopTick()
  if (amr.value) {
    // 不 destroy 会留着 AudioContext 和解码出来的 Float32Array，
    // 会话切来切去几十条语音就很占内存
    try {
      amr.value.stop()
      amr.value.destroy()
    } catch {
      /* 已经销毁过就算了 */
    }
    amr.value = null
  }
})
</script>

<template>
  <div class="voice" :class="{ 'voice-disabled': !hasAudio }">
    <button
      class="voice-btn"
      type="button"
      :disabled="!hasAudio || loading"
      :title="hasAudio ? '点击播放' : '音频未转存'"
      @click="toggle"
    >
      <el-icon v-if="loading" class="is-loading"><Loading /></el-icon>
      <el-icon v-else-if="playing"><VideoPause /></el-icon>
      <el-icon v-else><VideoPlay /></el-icon>
    </button>

    <span class="voice-label">{{ label }}</span>

    <!-- 播放进度。只在有时长且正在放的时候出现，静止时一条空进度条挺碍眼 -->
    <span v-if="duration > 0 && playing" class="voice-track">
      <span class="voice-track-fill" :style="{ width: `${Math.min((position / duration) * 100, 100)}%` }" />
    </span>

    <!-- 微信识别结果，实测一直是空的，有值才显示 -->
    <span v-if="recognition" class="voice-text">{{ recognition }}</span>
  </div>
</template>

<style scoped>
.voice {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 96px;
}

.voice-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.06);
  color: inherit;
  cursor: pointer;
  transition: background 0.15s;
}

.voice-btn:hover:not(:disabled) {
  background: rgba(0, 0, 0, 0.12);
}

.voice-btn:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.voice-label {
  font-variant-numeric: tabular-nums;
  opacity: 0.75;
  font-size: 13px;
}

.voice-track {
  position: relative;
  display: inline-block;
  width: 56px;
  height: 3px;
  border-radius: 2px;
  background: rgba(0, 0, 0, 0.12);
  overflow: hidden;
}

.voice-track-fill {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  background: currentColor;
  opacity: 0.55;
  transition: width 0.2s linear;
}

.voice-text {
  font-size: 13px;
}

.voice-disabled .voice-label {
  opacity: 0.5;
}
</style>
