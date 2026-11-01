// 건강 수집기. Health Connect를 1시간마다 지난 2일 범위로 읽는다(시간 범위 읽기. 변경 토큰은 나중).
// 같은 레코드를 다시 읽어도 (start, metric, category) identity가 같아 덮어쓴다.
const SPANS = ["steps", "steps_daily", "distance_daily", "active_kcal_daily", "total_kcal_daily", "floors_daily", "resting_heart_rate_daily", "sleep", "sleep_stage", "exercise"];
const POINTS = ["heart_rate", "spo2", "hrv", "body_temp", "weight"];
const NAME = { steps_daily: "steps", distance_daily: "distance", active_kcal_daily: "active_kcal", total_kcal_daily: "total_kcal", floors_daily: "floors", resting_heart_rate_daily: "resting_heart_rate" };
export default {
  start(ctx) {
    const run = () => {
      const to = ctx.now(); const from = new Date(to - 2 * 24 * 3600 * 1000);
      const want = ctx.settings.metrics || [];
      for (const m of SPANS) {
        if (want.length && !want.includes(NAME[m] || m)) continue;
        let rows; try { rows = ctx.read("health", { metric: m, from: from.toISOString(), to: to.toISOString() }); } catch (e) { ctx.log("warn", m + ": " + e.message); continue; }
        for (const r of rows) ctx.span.put("span", { start: r.start, end: r.end, metric: NAME[m] || m, value: r.value, category: r.category, extra: JSON.stringify(Object.assign({ unit: r.unit }, r.extra || {})) });
      }
      for (const m of POINTS) {
        if (want.length && !want.includes(m)) continue;
        let rows; try { rows = ctx.read("health", { metric: m, from: from.toISOString(), to: to.toISOString() }); } catch (e) { ctx.log("warn", m + ": " + e.message); continue; }
        for (const r of rows) ctx.event.emit("point", { time: r.time, metric: m, value: r.value, unit: r.unit });
      }
    };
    ctx.every("1h", run);
    run();
  },
  stop(ctx) {}
};
