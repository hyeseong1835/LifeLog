// 입력 활동량 수집기. input 소스가 1분마다 주는 횟수를 그대로 시점 행으로(time = 그 분의 시작). 0인 분은 소스가 내지 않는다.
export default {
  start(ctx) { ctx.on("input", ev => ctx.event.emit("input", { time: ev.time, keys: ev.keys, clicks: ev.clicks, scrolls: ev.scrolls, mouse_px: ev.mouse_px })); },
  stop(ctx) {}
};
