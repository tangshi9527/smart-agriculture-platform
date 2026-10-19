<template>
  <div ref="el" class="chart"></div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  points: { type: Array, default: () => [] }
})

const el = ref(null)
let chart = null

function render() {
  if (!chart) return
  const times = props.points.map(p => p.time)
  const temp = props.points.map(p => p.temperature)
  const hum = props.points.map(p => p.humidity)
  chart.setOption({
    grid: { left: 44, right: 18, top: 34, bottom: 28 },
    tooltip: { trigger: 'axis' },
    legend: { textStyle: { color: '#b8c8e0' }, data: ['温度℃', '湿度%'] },
    xAxis: {
      type: 'category', data: times,
      axisLine: { lineStyle: { color: '#1f3450' } },
      axisLabel: { color: '#8aa0c0', fontSize: 10 }
    },
    yAxis: {
      type: 'value', scale: true,
      axisLine: { lineStyle: { color: '#1f3450' } },
      splitLine: { lineStyle: { color: '#16243c' } },
      axisLabel: { color: '#8aa0c0', fontSize: 10 }
    },
    series: [
      { name: '温度℃', type: 'line', smooth: true, showSymbol: false, data: temp, itemStyle: { color: '#ff7a59' }, areaStyle: { opacity: 0.12, color: '#ff7a59' } },
      { name: '湿度%', type: 'line', smooth: true, showSymbol: false, data: hum, itemStyle: { color: '#3aa0ff' } }
    ]
  })
}

function resize() { chart && chart.resize() }

onMounted(() => {
  chart = echarts.init(el.value)
  render()
  window.addEventListener('resize', resize)
})
watch(() => props.points, render, { deep: true })
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart && chart.dispose()
})
</script>

<style scoped>
.chart { width: 100%; height: 100%; }
</style>
