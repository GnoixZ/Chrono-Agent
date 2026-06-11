const state = {
  data: null,
  activeDataKey: "conversationMemories",
  recorder: null,
  chunks: [],
  ws: null,
  wsRecorder: null,
  wsStream: null,
  sessionId: null,
  lastRecall: []
};

const keys = [
  ["audioEvents", "音频"],
  ["healthEvents", "健康"],
  ["conversationMemories", "会话"],
  ["speakerClusters", "人物"],
  ["speakerSegments", "转写"],
  ["personInsights", "洞察"],
  ["memoryCandidates", "候选记忆"],
  ["memoryItems", "长期记忆"],
  ["agentSessions", "Agent 会话"],
  ["agentMessages", "消息"],
  ["agentRuns", "Runs"],
  ["recallEvents", "召回"],
  ["modelJobs", "任务"],
  ["auditLogs", "审计"]
];

const $ = (id) => document.getElementById(id);

function userId() {
  return $("userId").value.trim() || "demo-user";
}

function toast(message) {
  const el = $("toast");
  el.textContent = message;
  el.classList.add("show");
  window.setTimeout(() => el.classList.remove("show"), 3200);
}

async function getAudioInputs() {
  if (!navigator.mediaDevices?.enumerateDevices) {
    return [];
  }
  try {
    const devices = await navigator.mediaDevices.enumerateDevices();
    return devices.filter(device => device.kind === "audioinput");
  } catch {
    return [];
  }
}

function setAudioButtonsDisabled(disabled) {
  $("recordStartBtn").disabled = disabled;
  $("wsStartBtn").disabled = disabled;
}

function setAudioStatus(recordingMessage, wsMessage) {
  $("recordingStatus").textContent = recordingMessage;
  $("wsStatus").textContent = wsMessage;
}

function friendlyMediaError(error) {
  if (!error) {
    return "录音启动失败，请稍后重试。";
  }
  if (error.name === "NotFoundError") {
    return "未检测到可用麦克风设备，请检查系统输入设备、远程桌面音频重定向或浏览器麦克风权限。";
  }
  if (error.name === "NotAllowedError") {
    return "浏览器未授予麦克风权限，请允许当前页面访问麦克风后重试。";
  }
  if (error.name === "NotReadableError") {
    return "麦克风正在被其他应用占用，或设备当前不可读取，请关闭占用后重试。";
  }
  if (error.name === "SecurityError") {
    return "当前页面环境不允许访问麦克风，请使用受支持的本地预览环境。";
  }
  return error.message || "录音启动失败，请稍后重试。";
}

async function refreshAudioAvailability() {
  if (!navigator.mediaDevices?.getUserMedia) {
    setAudioButtonsDisabled(true);
    setAudioStatus("当前浏览器环境不支持录音", "当前浏览器环境不支持录音");
    return false;
  }
  const audioInputs = await getAudioInputs();
  const hasAudioInput = audioInputs.length > 0;
  setAudioButtonsDisabled(!hasAudioInput);
  if (!hasAudioInput) {
    setAudioStatus("未检测到可用麦克风", "未检测到可用麦克风");
    return false;
  }
  if (!state.recorder || state.recorder.state === "inactive") {
    $("recordingStatus").textContent = "未录音";
  }
  if (!state.ws || state.ws.readyState === WebSocket.CLOSED) {
    $("wsStatus").textContent = "未连接";
  }
  return true;
}

async function ensureAudioReady() {
  if (!navigator.mediaDevices?.getUserMedia) {
    setAudioButtonsDisabled(true);
    setAudioStatus("当前浏览器环境不支持录音", "当前浏览器环境不支持录音");
    throw new Error("当前浏览器环境不支持麦克风录音。");
  }
  const audioInputs = await getAudioInputs();
  if (!audioInputs.length) {
    setAudioButtonsDisabled(true);
    setAudioStatus("未检测到可用麦克风", "未检测到可用麦克风");
    throw new Error("未检测到可用麦克风设备，请检查系统输入设备、远程桌面音频重定向或浏览器麦克风权限。");
  }
  return true;
}

async function api(path, options = {}) {
  const response = await fetch(path, options);
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `HTTP ${response.status}`);
  }
  return response.json();
}

async function refresh() {
  state.data = await api(`/api/demo/state?userId=${encodeURIComponent(userId())}`);
  renderAll();
}

function renderAll() {
  renderTimeline();
  renderSpeakers();
  renderMemoryCandidates();
  renderMemoryItems();
  renderChat();
  renderRecall(state.lastRecall.length ? state.lastRecall : []);
  renderDataTabs();
}

function renderTimeline() {
  const timeline = $("timeline");
  const conversations = state.data?.conversationMemories || [];
  const segments = state.data?.speakerSegments || [];
  const health = state.data?.healthEvents || [];
  if (!conversations.length && !health.length) {
    timeline.innerHTML = `<div class="event"><h3>暂无时间线</h3><p>上传录音、录入健康事件或发送 Agent 消息后，这里会展示结构化记录。</p></div>`;
    return;
  }
  const conversationHtml = conversations.map(item => {
    const relatedSegments = segments.filter(segment => segment.audioEventId === item.sourceAudioEventId).slice(0, 4);
    return `<article class="event">
      <div class="meta">
        <span class="pill green">${item.status || "completed"}</span>
        <span class="pill">${formatTime(item.createdAt)}</span>
        ${item.discarded ? `<span class="pill red">discarded</span>` : ""}
      </div>
      <h3>${escapeHtml(item.title || "未命名会话")}</h3>
      <p>${escapeHtml(item.overview || "")}</p>
      ${relatedSegments.map(segment => `<p><strong>${escapeHtml(segment.displayName || "Unknown")}</strong>：${escapeHtml(segment.transcript || "")}</p>`).join("")}
    </article>`;
  }).join("");
  const healthHtml = health.slice(0, 6).map(item => `<article class="event">
      <div class="meta"><span class="pill amber">health</span><span class="pill">${formatTime(item.measuredAt)}</span></div>
      <h3>${escapeHtml(item.eventType)}</h3>
      <p>${escapeHtml(displayHealthValue(item))}</p>
    </article>`).join("");
  timeline.innerHTML = conversationHtml + healthHtml;
}

function renderSpeakers() {
  const container = $("speakers");
  const speakers = state.data?.speakerClusters || [];
  if (!speakers.length) {
    container.innerHTML = `<div class="small-card"><p>录音分析后会出现 Unknown Person，可在这里标注身份。</p></div>`;
    return;
  }
  container.innerHTML = speakers.map(speaker => `<div class="small-card">
    <strong>${escapeHtml(speaker.displayName)}</strong>
    <div class="meta">
      <span class="pill ${speaker.userLabeled ? "green" : "amber"}">${speaker.userLabeled ? "已标注" : "未注册"}</span>
      <span class="pill">${formatTime(speaker.lastSeenAt)}</span>
    </div>
    <form class="inline-form" onsubmit="labelSpeaker(event, '${speaker.id}')">
      <input name="displayName" placeholder="例如：同事张三 / 妈妈">
      <button type="submit">标注</button>
    </form>
  </div>`).join("");
}

function renderMemoryCandidates() {
  const container = $("memoryCandidates");
  const candidates = state.data?.memoryCandidates || [];
  if (!candidates.length) {
    container.innerHTML = `<div class="small-card"><p>音频或 Agent 回复产生候选记忆后会显示在这里。</p></div>`;
    return;
  }
  container.innerHTML = candidates.map(candidate => `<div class="small-card">
    <strong>${escapeHtml(candidate.memoryType || "memory")}</strong>
    <p>${escapeHtml(candidate.content || "")}</p>
    <div class="meta">
      <span class="pill">${escapeHtml(candidate.decision || "")}</span>
      <span class="pill">${Number(candidate.confidence || 0).toFixed(2)}</span>
    </div>
    <div class="button-row">
      <button onclick="acceptMemory('${candidate.id}')">接受</button>
      <button onclick="rejectMemory('${candidate.id}')">拒绝</button>
    </div>
  </div>`).join("");
}

function renderMemoryItems() {
  const container = $("memoryItems");
  const memories = state.data?.memoryItems || [];
  if (!memories.length) {
    container.innerHTML = `<div class="small-card"><p>接受候选记忆后，长期记忆会在这里展示。</p></div>`;
    return;
  }
  container.innerHTML = memories.map(memory => `<div class="small-card">
    <strong>${escapeHtml(memory.memoryType || "memory")}</strong>
    <p>${escapeHtml(memory.content || "")}</p>
    <div class="meta"><span class="pill green">${escapeHtml(memory.source || "")}</span><span class="pill">${formatTime(memory.createdAt)}</span></div>
  </div>`).join("");
}

function renderChat() {
  const log = $("chatLog");
  const messages = (state.data?.agentMessages || []).slice().reverse();
  if (!messages.length) {
    log.innerHTML = `<div class="message assistant">上传录音、录入健康事件或接受记忆后，可以问我：“最近我的状态有什么线索？”</div>`;
    return;
  }
  log.innerHTML = messages.map(message => `<div class="message ${message.role === "user" ? "user" : "assistant"}">${escapeHtml(message.content || "")}</div>`).join("");
}

function renderRecall(recalled) {
  const container = $("recallContext");
  if (!recalled.length) {
    const events = state.data?.recallEvents || [];
    container.innerHTML = events.slice(0, 8).map(event => `<div class="recall-item">
      <div class="meta"><span class="pill">${escapeHtml(event.recallType)}</span><span class="pill">${event.score}</span></div>
      <p>${escapeHtml(event.reason || "")}</p>
    </div>`).join("") || `<div class="recall-item"><p>发送 Agent 消息后展示本轮召回来源。</p></div>`;
    return;
  }
  container.innerHTML = recalled.map(item => `<div class="recall-item">
    <div class="meta"><span class="pill">${escapeHtml(item.sourceType)}</span><span class="pill">${Number(item.score || 0).toFixed(2)}</span></div>
    <p>${escapeHtml(item.content || "")}</p>
  </div>`).join("");
}

function renderDataTabs() {
  const tabs = $("dataTabs");
  tabs.innerHTML = keys.map(([key, label]) => `<button class="tab ${state.activeDataKey === key ? "active" : ""}" onclick="showData('${key}')">${label} ${count(key)}</button>`).join("");
  $("dataViewer").textContent = JSON.stringify(state.data?.[state.activeDataKey] || [], null, 2);
}

function showData(key) {
  state.activeDataKey = key;
  renderDataTabs();
}

function count(key) {
  const value = state.data?.[key];
  return Array.isArray(value) ? value.length : 0;
}

async function uploadBlob(blob, fileName) {
  const form = new FormData();
  form.append("userId", userId());
  form.append("file", blob, fileName);
  const result = await api("/api/demo/audio", { method: "POST", body: form });
  toast(`处理完成：${result.title}`);
  await refresh();
}

async function startRecording() {
  await ensureAudioReady();

  const stream = await navigator.mediaDevices.getUserMedia({ audio: true });

  state.chunks = [];
  state.recorder = new MediaRecorder(stream);
  state.recorder.ondataavailable = event => {
    if (event.data.size > 0) state.chunks.push(event.data);
  };
  state.recorder.onstop = async () => {
    stream.getTracks().forEach(track => track.stop());
    const blob = new Blob(state.chunks, { type: "audio/webm" });
    $("recordingStatus").textContent = `录音完成，上传 ${Math.round(blob.size / 1024)} KB`;
    await uploadBlob(blob, "browser-recording.webm");
    $("recordStartBtn").disabled = false;
    $("recordStopBtn").disabled = true;
  };
  state.recorder.start();
  $("recordingStatus").textContent = "录音中";
  $("recordStartBtn").disabled = true;
  $("recordStopBtn").disabled = false;
}

function stopRecording() {
  if (state.recorder && state.recorder.state !== "inactive") {
    state.recorder.stop();
  }
}

async function startWebSocketStream() {
  await ensureAudioReady();
  state.wsStream = await navigator.mediaDevices.getUserMedia({ audio: true });

  const protocol = window.location.protocol === "https:" ? "wss" : "ws";
  state.ws = new WebSocket(`${protocol}://${window.location.host}/ws/audio?userId=${encodeURIComponent(userId())}`);
  state.ws.binaryType = "arraybuffer";
  state.ws.onopen = () => {
    $("wsStatus").textContent = "WebSocket 已连接，开始发送音频片段";
    state.wsRecorder = new MediaRecorder(state.wsStream);
    state.wsRecorder.ondataavailable = async event => {
      if (event.data.size > 0 && state.ws?.readyState === WebSocket.OPEN) {
        state.ws.send(await event.data.arrayBuffer());
      }
    };
    state.wsRecorder.start(1000);
    $("wsStartBtn").disabled = true;
    $("wsStopBtn").disabled = false;
  };
  state.ws.onmessage = async event => {
    $("wsStatus").textContent = event.data;
    try {
      const payload = JSON.parse(event.data);
      if (payload.type === "processing_completed") {
        toast("WebSocket 音频处理完成");
        await refresh();
      }
      if (payload.type === "error") {
        toast(payload.message || "WebSocket 处理失败");
      }
    } catch {
      $("wsStatus").textContent = event.data;
    }
  };
  state.ws.onclose = () => {
    $("wsStartBtn").disabled = false;
    $("wsStopBtn").disabled = true;
    state.wsStream?.getTracks().forEach(track => track.stop());
  };
}

function stopWebSocketStream() {
  if (state.wsRecorder && state.wsRecorder.state !== "inactive") {
    state.wsRecorder.stop();
  }
  if (state.ws?.readyState === WebSocket.OPEN) {
    state.ws.send(JSON.stringify({ type: "stop", fileName: "browser-stream.webm" }));
    $("wsStatus").textContent = "已发送停止信号，等待处理";
  }
}

async function labelSpeaker(event, speakerId) {
  event.preventDefault();
  const displayName = new FormData(event.target).get("displayName");
  if (!displayName) return;
  await api(`/api/demo/speakers/${speakerId}/label`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ userId: userId(), displayName })
  });
  toast("人物标签已保存");
  await refresh();
}

async function acceptMemory(id) {
  await api(`/api/demo/memory-candidates/${id}/accept`, { method: "POST" });
  toast("候选记忆已接受");
  await refresh();
}

async function rejectMemory(id) {
  await api(`/api/demo/memory-candidates/${id}/reject`, { method: "POST" });
  toast("候选记忆已拒绝");
  await refresh();
}

function displayHealthValue(item) {
  const value = item.valueText || item.valueNumeric || "";
  return `${value}${item.unit || ""} · ${item.source || "manual"}`;
}

function formatTime(value) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString();
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

$("refreshBtn").addEventListener("click", () => refresh().catch(error => toast(error.message)));

$("uploadForm").addEventListener("submit", async event => {
  event.preventDefault();
  const file = $("audioFile").files[0];
  if (!file) {
    toast("请选择录音文件");
    return;
  }
  try {
    await uploadBlob(file, file.name);
  } catch (error) {
    toast(error.message);
  }
});

$("recordStartBtn").addEventListener("click", () => startRecording().catch(error => {
  const message = friendlyMediaError(error);
  $("recordingStatus").textContent = message;
  toast(message);
}));
$("recordStopBtn").addEventListener("click", stopRecording);
$("wsStartBtn").addEventListener("click", () => startWebSocketStream().catch(error => {
  const message = friendlyMediaError(error);
  $("wsStatus").textContent = message;
  toast(message);
}));
$("wsStopBtn").addEventListener("click", stopWebSocketStream);

$("healthForm").addEventListener("submit", async event => {
  event.preventDefault();
  const rawValue = $("healthValue").value.trim();
  const numeric = Number(rawValue);
  const payload = {
    userId: userId(),
    eventType: $("healthType").value,
    measuredAt: new Date().toISOString(),
    valueNumeric: Number.isFinite(numeric) && rawValue !== "" ? numeric : null,
    valueText: Number.isFinite(numeric) && rawValue !== "" ? null : rawValue,
    unit: $("healthUnit").value.trim(),
    source: "manual"
  };
  try {
    await api("/api/demo/health", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    toast("健康事件已写入");
    await refresh();
  } catch (error) {
    toast(error.message);
  }
});

$("agentForm").addEventListener("submit", async event => {
  event.preventDefault();
  const content = $("agentMessage").value.trim();
  if (!content) return;
  try {
    const result = await api("/api/demo/agent/messages", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ userId: userId(), conversationSessionId: state.sessionId, content })
    });
    state.sessionId = result.conversationSessionId;
    state.lastRecall = result.recalledContext || [];
    $("agentMessage").value = "";
    await refresh();
    renderRecall(state.lastRecall);
  } catch (error) {
    toast(error.message);
  }
});

refresh()
  .then(() => refreshAudioAvailability())
  .catch(error => toast(error.message));

if (navigator.mediaDevices?.addEventListener) {
  navigator.mediaDevices.addEventListener("devicechange", () => {
    refreshAudioAvailability().catch(() => {});
  });
}
