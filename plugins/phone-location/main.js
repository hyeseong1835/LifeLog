// 위치 점. location 소스의 갱신을 시점 행으로. 좌표만, 장소 판정 없음.
export default {
  start(ctx) { ctx.on("location", ev => ctx.event.emit("point", { time: ev.time, lat: ev.lat, lon: ev.lon, accuracy: ev.accuracy })); },
  stop(ctx) {}
};
