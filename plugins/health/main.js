// 건강 수집기. 1시간마다: 집계·세션 지표는 지난 2일 범위를 다시 읽고(작다), 시점 지표는 변경 토큰으로 새 레코드만 읽는다.
// 같은 레코드를 다시 읽어도 (start, metric, category) identity가 같아 덮어쓴다. Health Connect에서 지워진 시점 행은 지우지 않는다.
const SPANS = ["steps", "steps_daily", "distance_daily", "active_kcal_daily", "total_kcal_daily", "floors_daily", "resting_heart_rate_daily", "sleep", "sleep_stage", "exercise"];
const POINTS = ["heart_rate", "spo2", "hrv", "body_temp", "weight"];
// 삼성 헬스 Data SDK(read:shealth, 선택). 행 모양이 같아 start가 있으면 구간, 없으면 시점으로 넣는다. 기본은 Health Connect에 없는 것만.
// 삼성 헬스는 수면을 Health Connect로 넘기지 않아 sh_sleep·sh_sleep_stage가 필요하다. sh_steps는 HC 일별 값이 없을 때 위젯이 쓴다
const SHEALTH_DEFAULT = ["energy_score", "sleep_score", "sh_sleep", "sh_sleep_stage", "sh_steps", "skin_temperature", "active_time", "blood_pressure", "blood_glucose", "body_composition", "water", "nutrition", "nutrition_kcal", "sleep_apnea", "irregular_heart_rhythm", "steps_goal", "active_kcal_goal", "active_time_goal", "water_goal", "sleep_goal", "nutrition_goal"];
// 삼성 헬스 시점 행의 숫자 extra(혈압 이완기·체지방 등)는 "지표.키" 시점으로 따로 남긴다. point 출력에는 extra 열이 없다.
const EXTRA_UNIT = { diastolic: "mmHg", mean: "mmHg", pulse: "bpm", height: "cm", body_fat: "%", skeletal_muscle: "%", muscle_mass: "kg", body_fat_mass: "kg", fat_free_mass: "kg", skeletal_muscle_mass: "kg", total_body_water: "kg", bmi: "", bmr: "kcal" };
const NAME = { steps_daily: "steps", distance_daily: "distance", active_kcal_daily: "active_kcal", total_kcal_daily: "total_kcal", floors_daily: "floors", resting_heart_rate_daily: "resting_heart_rate" };
export default {
  start(ctx) {
    let first = true;
    const run = () => {
      const to = ctx.now(); const from = new Date(to - 2 * 24 * 3600 * 1000);
      const want = ctx.settings.metrics || [];
      const counts = [];
      for (const m of SPANS) {
        if (want.length && !want.includes(NAME[m] || m)) continue;
        // 시간별 걸음은 한 번만 30일을 다시 읽는다(정각 정렬 고침 뒤 지운 행을 채움)
        const backfill = m === "steps" && !ctx.state.get("steps_hourly_backfill_v1");
        const f = backfill ? new Date(to - 30 * 24 * 3600 * 1000) : from;
        let rows; try { rows = ctx.read("health", { metric: m, from: f.toISOString(), to: to.toISOString() }); } catch (e) { ctx.log("warn", m + ": " + e.message); continue; }
        if (backfill) { ctx.state.set("steps_hourly_backfill_v1", true); ctx.log("info", "시간별 걸음 30일 다시 읽음: " + rows.length + "건"); }
        counts.push(m + " " + rows.length);
        for (const r of rows) ctx.span.put("span", { start: r.start, end: r.end, metric: NAME[m] || m, value: r.value, category: r.category, extra: JSON.stringify(Object.assign({ unit: r.unit }, r.extra || {})) });
      }
      if (first) { ctx.log("info", "구간 지표 건수: " + counts.join(", ")); first = false; }
      // 시점 지표는 변경 토큰으로 새 레코드만 읽는다. 토큰이 없거나 만료되면 소스가 2일치를 다시 준다(reset)
      for (const m of POINTS) {
        if (want.length && !want.includes(m)) continue;
        let res; try { res = ctx.read("health", { metric: m, token: ctx.state.get("token:" + m) || "" }); } catch (e) { ctx.log("warn", m + ": " + e.message); continue; }
        for (const r of res.rows) ctx.event.emit("point", { time: r.time, metric: m, value: r.value, unit: r.unit });
        ctx.state.set("token:" + m, res.token);
        if (res.rows.length || res.reset) ctx.log("info", m + ": " + res.rows.length + "건" + (res.reset ? " (토큰 새로 발급, 2일치)" : " (변경분)"));
      }
    };
    const runSh = () => {
      if (!ctx.has("read:shealth")) return;
      const to = ctx.now(); const from = new Date(to - 2 * 24 * 3600 * 1000);
      const list = Array.isArray(ctx.settings.shealth_metrics) && ctx.settings.shealth_metrics.length ? ctx.settings.shealth_metrics : SHEALTH_DEFAULT;
      let n = 0;
      for (const m of list) {
        let rows; try { rows = ctx.read("shealth", { metric: m, from: from.toISOString(), to: to.toISOString() }); } catch (e) { ctx.log("warn", "shealth " + m + ": " + e.message); continue; }
        for (const r of rows) {
          if (r.start) ctx.span.put("span", { start: r.start, end: r.end, metric: m, value: r.value, category: r.category, extra: JSON.stringify(Object.assign({ unit: r.unit }, r.extra || {})) });
          else {
            ctx.event.emit("point", { time: r.time, metric: m, value: r.value, unit: r.unit });
            for (const [k, v] of Object.entries(r.extra || {})) if (typeof v === "number") { ctx.event.emit("point", { time: r.time, metric: m + "." + k, value: v, unit: EXTRA_UNIT[k] ?? "" }); n++; }
          }
          n++;
        }
      }
      if (n) ctx.log("info", "shealth " + n + "건");
    };
    ctx.every("1h", run);
    ctx.every("1h", runSh);
    run();
    runSh();
  },
  stop(ctx) {}
};
