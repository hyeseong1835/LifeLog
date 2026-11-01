// 화면 켬·끔·잠금 해제. screen 소스의 이벤트를 시점 행으로.
export default {
  start(ctx) { ctx.on("screen", ev => ctx.event.emit("screen", { time: ev.time, kind: ev.kind })); },
  stop(ctx) {}
};
