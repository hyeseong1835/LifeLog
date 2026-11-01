// 전원·잠금 수집기. power 소스의 이벤트를 시점 행으로.
export default {
  start(ctx) { ctx.on("power", ev => ctx.event.emit("power", { time: ev.time, kind: ev.kind })); },
  stop(ctx) {}
};
