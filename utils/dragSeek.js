export function dragBounds(useChapterTrack, chapter, totalDuration) {
  if (useChapterTrack && chapter) {
    const duration = chapter.end - chapter.start
    return { duration, minTime: chapter.start, maxTime: chapter.start + duration }
  }
  return { duration: totalDuration, minTime: 0, maxTime: totalDuration }
}

export function dragSeekTime({ startTime, startX, pageX, trackWidth, duration, minTime, maxTime }) {
  const clamp = (value) => Math.min(maxTime, Math.max(minTime, value))
  if (!(trackWidth > 0) || !(duration > 0)) return clamp(startTime)
  return clamp(startTime + (duration / trackWidth) * (pageX - startX))
}
