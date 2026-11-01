// 건강 수집기. 1시간마다: 집계·세션 지표는 지난 2일 범위를 다시 읽고(작다), 시점 지표는 변경 토큰으로 새 레코드만 읽는다.
// 같은 레코드를 다시 읽어도 (start, metric, category) identity가 같아 덮어쓴다. Health Connect에서 지워진 시점 행은 지우지 않는다.
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
      // 시점 지표는 변경 토큰으로 새 레코드만 읽는다. 토큰이 없거나 만료되면 소스가 2일치를 다시 준다(reset)
      for (const m of POINTS) {
        if (want.length && !want.includes(m)) continue;
        let res; try { res = ctx.read("health", { metric: m, token: ctx.state.get("token:" + m) || "" }); } catch (e) { ctx.log("warn", m + ": " + e.message); continue; }
        for (const r of res.rows) ctx.event.emit("point", { time: r.time, metric: m, value: r.value, unit: r.unit });
        ctx.state.set("token:" + m, res.token);
        if (res.rows.length || res.reset) ctx.log("info", m + ": " + res.rows.length + "건" + (res.reset ? " (토큰 새로 발급, 2일치)" : " (변경분)"));
      }
    };
    ctx.every("1h", run);
    run();
  },
  stop(ctx) {}
};
