// LifeLog VS Code 확장. 편집기 전환·창 포커스·1분 하트비트를 %LOCALAPPDATA%\LifeLog\vscode\events.jsonl에 덧붙인다.
// 파일 내용은 보지 않는다. 읽는 쪽은 plugins/vscode(수집기 플러그인)이다.
const vscode = require("vscode");
const fs = require("fs");
const path = require("path");
const os = require("os");

function dir() {
  const base = process.platform === "win32" ? (process.env.LOCALAPPDATA || os.homedir()) : path.join(os.homedir(), ".local", "share");
  return path.join(base, "LifeLog", "vscode");
}
function write(ev) {
  try { fs.mkdirSync(dir(), { recursive: true }); fs.appendFileSync(path.join(dir(), "events.jsonl"), JSON.stringify(ev) + "\n"); } catch (e) { /* 기록 실패는 무시 */ }
}
function current() {
  const ed = vscode.window.activeTextEditor;
  if (!ed || ed.document.uri.scheme !== "file") return null;
  const ws = vscode.workspace.getWorkspaceFolder(ed.document.uri);
  const fsPath = ed.document.uri.fsPath;
  const project = ws ? ws.name : path.basename(path.dirname(fsPath));
  const file = (ws ? path.relative(ws.uri.fsPath, fsPath) : path.basename(fsPath)).replace(/\\/g, "/");
  return { project, file, language: ed.document.languageId };
}
function emit(kind) {
  write(Object.assign({ time: new Date().toISOString(), kind, focused: vscode.window.state.focused }, current() || {}));
}
let timer = null;
function activate(ctx) {
  ctx.subscriptions.push(vscode.window.onDidChangeActiveTextEditor(() => emit("editor")));
  ctx.subscriptions.push(vscode.window.onDidChangeWindowState(s => emit(s.focused ? "focus" : "blur")));
  timer = setInterval(() => { if (vscode.window.state.focused) emit("heartbeat"); }, 60000);
  emit("start");
}
function deactivate() { if (timer) clearInterval(timer); write({ time: new Date().toISOString(), kind: "stop", focused: false }); }
module.exports = { activate, deactivate };
