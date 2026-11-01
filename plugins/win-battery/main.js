// 배터리·전원 수집기. battery 소스가 값이 바뀔 때(최소 10분마다) 주는 상태를 시점 행으로.
export default {
  start(ctx) { ctx.on("battery", ev => ctx.event.emit("battery", { time: ev.time, percent: ev.percent, ac: ev.ac })); },
  stop(ctx) {}
};
