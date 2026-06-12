const state = {
  data: null,
  activeDataKey: "conversationMemories",
  recorder: null,
  chunks: [],
  ws: null,
  wsRecorder: null,
  wsStream: null,
  wsAudioContext: null,
  wsAnalyser: null,
  wsSourceNode: null,
  wsVadFrameId: null,
  wsSpeechSince: null,
  wsSilenceSince: null,
  wsLastVadUiUpdate: 0,
  sessionId: null,
  lastRecall: [],
  wsProgress: createWsProgressState()
};

const keys = [
  ["audioEvents", "音频"],
  ["audioStreamSessions", "流会话"],
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

const WS_AUDIO_TIMESLICE_MS = 1000;
const WS_VAD_SPEECH_THRESHOLD = 0.035;
const WS_VAD_SPEECH_HOLD_MS = 1200;
const WS_VAD_SILENCE_TIMEOUT_MS = 60000;

function userId() {
  return $("userId").value.trim() || "demo-user";
}

function toast(message) {
  const el = $("toast");
  el.textContent = message;
  el.classList.add("show");
  window.setTimeout(() => el.classList.remove("show"), 3200);
}

function createWsProgressState() {
  return {
    connected: false,
    streamSessionId: null,
    streamOpenedAt: null,
    listeningStartedAt: null,
    sessionStartedAt: null,
    sessionState: "idle",
    sessionStartRequested: false,
    silenceMs: 0,
    silenceTimeoutMs: WS_VAD_SILENCE_TIMEOUT_MS,
    latestRms: 0,
    closeReason: null,
    stopRequested: false,
    phase: "idle",
    headline: "等待开始流式录音",
    detail: "这里会实时展示监听、人声触发、窗口切分和处理进度。",
    chunkCount: 0,
    totalBytes: 0,
    queuedWindows: 0,
    completedWindows: 0,
    failedWindows: 0,
    pendingWindows: 0,
    currentWindowIndex: 0,
    lastAudioEventId: null,
    lastConversationMemoryId: null,
    lastProcessingStatus: null,
    windows: [],
    transcripts: [],
    events: []
  };
}

function resetWsProgress(overrides = {}) {
  state.wsProgress = {
    ...createWsProgressState(),
    ...overrides
  };
  renderWsProgress();
}

function addWsEvent(label, detail) {
  const item = {
    time: new Date().toISOString(),
    label,
    detail
  };
  state.wsProgress.events = [item, ...(state.wsProgress.events || [])].slice(0, 8);
}

function setWsHeadline(phase, headline, detail) {
  state.wsProgress.phase = phase;
  state.wsProgress.headline = headline;
  state.wsProgress.detail = detail;
}

function setListeningHeadline(detail) {
  setWsHeadline(
    "listening",
    "流会话已建立，正在监听连续人声",
    detail || "连接已建立，本地检测到连续人声后才会开始业务会话。"
  );
}

function wsConversationStarted() {
  return Boolean(state.wsProgress.sessionStartedAt);
}

function stopWsVadLoop() {
  if (state.wsVadFrameId) {
    cancelAnimationFrame(state.wsVadFrameId);
    state.wsVadFrameId = null;
  }
  state.wsSpeechSince = null;
  state.wsSilenceSince = null;
  state.wsLastVadUiUpdate = 0;
}

function cleanupWsAudioGraph() {
  stopWsVadLoop();
  state.wsSourceNode?.disconnect();
  state.wsAnalyser?.disconnect?.();
  state.wsSourceNode = null;
  state.wsAnalyser = null;
  if (state.wsAudioContext) {
    state.wsAudioContext.close().catch(() => {});
    state.wsAudioContext = null;
  }
}

function cleanupWsMedia() {
  if (state.wsRecorder && state.wsRecorder.state !== "inactive") {
    state.wsRecorder.stop();
  }
  state.wsRecorder = null;
  cleanupWsAudioGraph();
  state.wsStream?.getTracks().forEach(track => track.stop());
  state.wsStream = null;
}

function startWsRecorder() {
  if (!state.wsStream) {
    return;
  }
  if (state.wsRecorder && state.wsRecorder.state !== "inactive") {
    return;
  }
  state.wsRecorder = new MediaRecorder(state.wsStream);
  state.wsRecorder.ondataavailable = async event => {
    if (event.data.size > 0 && state.ws?.readyState === WebSocket.OPEN) {
      state.ws.send(await event.data.arrayBuffer());
    }
  };
  state.wsRecorder.start(WS_AUDIO_TIMESLICE_MS);
}

function triggerWsSessionStart() {
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
    return;
  }
  if (state.wsProgress.sessionStartRequested || wsConversationStarted()) {
    return;
  }
  state.wsProgress.sessionStartRequested = true;
  state.ws.send(JSON.stringify({ type: "session_started" }));
  $("wsStatus").textContent = "检测到连续人声，正在启动业务会话";
  setWsHeadline("capturing", "检测到连续人声，正在启动业务会话", "已发送 session_started 控制消息，等待服务端确认后开始上传音频片段。");
  addWsEvent("触发人声", "已发送 session_started");
  renderWsProgress();
}

function stopWebSocketSession(reason, detail) {
  if (state.wsProgress.stopRequested) {
    return;
  }
  if (state.wsRecorder && state.wsRecorder.state !== "inactive") {
    state.wsRecorder.stop();
  }
  stopWsVadLoop();
  state.wsProgress.stopRequested = true;
  state.wsProgress.sessionState = "post_processing";
  state.wsProgress.silenceMs = reason === "silence_timeout_60s" ? WS_VAD_SILENCE_TIMEOUT_MS : state.wsProgress.silenceMs;
  if (state.ws?.readyState === WebSocket.OPEN) {
    state.ws.send(JSON.stringify({ type: "stop", fileName: "browser-stream.webm", reason }));
    $("wsStatus").textContent = detail;
    setWsHeadline("draining", detail, "前端不再继续采集，后台正在处理剩余窗口。");
    addWsEvent(reason === "silence_timeout_60s" ? "静音自动结束" : "强制结束", reason);
    renderWsProgress();
  }
}

function startWsVadLoop() {
  if (!state.wsStream) {
    return;
  }
  cleanupWsAudioGraph();
  state.wsAudioContext = new AudioContext();
  state.wsSourceNode = state.wsAudioContext.createMediaStreamSource(state.wsStream);
  state.wsAnalyser = state.wsAudioContext.createAnalyser();
  state.wsAnalyser.fftSize = 2048;
  state.wsAnalyser.smoothingTimeConstant = 0.85;
  state.wsSourceNode.connect(state.wsAnalyser);
  const samples = new Uint8Array(state.wsAnalyser.fftSize);
  const loop = () => {
    if (!state.wsAnalyser || state.ws?.readyState !== WebSocket.OPEN || state.wsProgress.stopRequested) {
      state.wsVadFrameId = null;
      return;
    }
    state.wsAnalyser.getByteTimeDomainData(samples);
    let energy = 0;
    for (const sample of samples) {
      const normalized = (sample - 128) / 128;
      energy += normalized * normalized;
    }
    const rms = Math.sqrt(energy / samples.length);
    const now = performance.now();
    state.wsProgress.latestRms = rms;
    if (!wsConversationStarted()) {
      if (rms >= WS_VAD_SPEECH_THRESHOLD) {
        if (state.wsSpeechSince == null) {
          state.wsSpeechSince = now;
          setListeningHeadline(`检测到人声，保持连续说话约 ${(WS_VAD_SPEECH_HOLD_MS / 1000).toFixed(1)} 秒后开始业务会话。`);
          renderWsProgress();
        }
        if (now - state.wsSpeechSince >= WS_VAD_SPEECH_HOLD_MS) {
          triggerWsSessionStart();
        }
      } else if (state.wsSpeechSince != null) {
        state.wsSpeechSince = null;
        if (!state.wsProgress.sessionStartRequested) {
          setListeningHeadline("刚才的人声未达到连续阈值，继续等待新的说话片段。");
          renderWsProgress();
        }
      }
      state.wsVadFrameId = requestAnimationFrame(loop);
      return;
    }

    if (rms >= WS_VAD_SPEECH_THRESHOLD) {
      if (state.wsSilenceSince != null) {
        addWsEvent("恢复人声", "静音计时已清零");
      }
      state.wsSilenceSince = null;
      state.wsProgress.silenceMs = 0;
      if (state.wsSpeechSince == null) {
        state.wsSpeechSince = now;
      }
    } else {
      state.wsSpeechSince = null;
      if (state.wsSilenceSince == null) {
        state.wsSilenceSince = now;
        addWsEvent("开始静音", "累计 60 秒后自动结束会话");
      }
      state.wsProgress.silenceMs = Math.max(0, now - state.wsSilenceSince);
      const remainingMs = Math.max(0, WS_VAD_SILENCE_TIMEOUT_MS - state.wsProgress.silenceMs);
      if (state.wsProgress.silenceMs >= WS_VAD_SILENCE_TIMEOUT_MS) {
        stopWebSocketSession("silence_timeout_60s", "连续静音 60 秒，已自动结束业务会话");
        return;
      }
      if (now - state.wsLastVadUiUpdate >= 1000) {
        state.wsLastVadUiUpdate = now;
        setWsHeadline(
          "in_session",
          "业务会话进行中",
          `当前静音 ${(state.wsProgress.silenceMs / 1000).toFixed(0)} 秒，距离自动结束还剩 ${(remainingMs / 1000).toFixed(0)} 秒。`
        );
        renderWsProgress();
      }
    }
    state.wsVadFrameId = requestAnimationFrame(loop);
  };
  state.wsVadFrameId = requestAnimationFrame(loop);
}

function updateWindow(windowIndex, updates = {}) {
  if (!windowIndex) {
    return;
  }
  const windows = state.wsProgress.windows || [];
  const found = windows.find(item => item.windowIndex === windowIndex);
  if (found) {
    Object.assign(found, updates);
  } else {
    windows.unshift({
      windowIndex,
      status: "queued",
      label: `窗口 ${windowIndex}`,
      audioEventId: null,
      conversationMemoryId: null,
      finalWindow: false,
      updatedAt: new Date().toISOString(),
      ...updates
    });
  }
  state.wsProgress.windows = windows
    .slice()
    .sort((left, right) => right.windowIndex - left.windowIndex)
    .slice(0, 8);
}

function updatePendingWindows() {
  state.wsProgress.pendingWindows = Math.max(0, state.wsProgress.queuedWindows - state.wsProgress.completedWindows - state.wsProgress.failedWindows);
}

function findWindowByAudioEventId(audioEventId) {
  if (!audioEventId) {
    return null;
  }
  return (state.wsProgress.windows || []).find(item => item.audioEventId === audioEventId) || null;
}

function updateWsProgressFromPayload(payload) {
  if (!payload?.type) {
    return;
  }
  if (payload.type === "stream_opened") {
    resetWsProgress({
      connected: true,
      streamSessionId: payload.streamSessionId || null,
      streamOpenedAt: new Date().toISOString(),
      listeningStartedAt: payload.listeningStartedAt || new Date().toISOString(),
      sessionState: payload.sessionState || "listening",
      phase: "listening",
      headline: "流会话已建立，正在监听连续人声",
      detail: `连接 ${shortId(payload.streamSessionId)} 已建立，检测到连续人声后才会开始业务会话。`
    });
    addWsEvent("连接已建立", `Session ${shortId(payload.streamSessionId)}`);
    return;
  }

  if (payload.type === "session_started") {
    state.wsProgress.sessionStartedAt = payload.sessionStartedAt || new Date().toISOString();
    state.wsProgress.sessionState = payload.sessionState || "in_session";
    state.wsProgress.sessionStartRequested = false;
    state.wsProgress.silenceMs = 0;
    state.wsSilenceSince = null;
    startWsRecorder();
    setWsHeadline(
      "in_session",
      "检测到连续人声，业务会话已开始",
      `会话开始时间 ${formatTime(state.wsProgress.sessionStartedAt)}，现在开始发送音频片段。`
    );
    addWsEvent("会话开始", `Session ${shortId(payload.streamSessionId)}`);
    return;
  }

  if (payload.type === "listening_stopped") {
    state.wsProgress.connected = false;
    state.wsProgress.sessionState = payload.sessionState || "closed";
    setWsHeadline("idle", "监听已结束，未进入业务会话", "本次仅建立了连接和监听态，没有触发会话开始。");
    addWsEvent("结束监听", `Session ${shortId(payload.streamSessionId)}`);
    return;
  }

  if (payload.type === "chunk_received") {
    state.wsProgress.chunkCount = Number(payload.chunks || 0);
    state.wsProgress.totalBytes = Number(payload.bytes || 0);
    if (!state.wsProgress.stopRequested) {
      setWsHeadline(
        state.wsProgress.queuedWindows > 0 ? "streaming" : "in_session",
        `业务会话进行中 · ${state.wsProgress.chunkCount} 个 chunk`,
        `累计 ${formatBytes(state.wsProgress.totalBytes)}，当前窗口写入中。`
      );
    }
    return;
  }

  if (payload.type === "incremental_transcript") {
    state.wsProgress.transcripts = [{
      sequence: Number(payload.sequence || 0),
      transcript: payload.transcript || "",
      stability: Number(payload.stability || 0),
      isFinal: Boolean(payload.isFinal),
      time: new Date().toISOString()
    }, ...(state.wsProgress.transcripts || [])].slice(0, 6);
    addWsEvent("增量转写", `#${payload.sequence || state.wsProgress.transcripts.length}`);
    return;
  }

  if (payload.type === "transcript_error") {
    addWsEvent("转写暂不可用", payload.message || "model service unavailable");
    return;
  }

  if (payload.type === "window_processing_started") {
    const windowIndex = Number(payload.windowIndex || state.wsProgress.currentWindowIndex + 1 || 1);
    state.wsProgress.currentWindowIndex = Math.max(state.wsProgress.currentWindowIndex, windowIndex);
    state.wsProgress.queuedWindows = Math.max(state.wsProgress.queuedWindows, windowIndex);
    state.wsProgress.lastAudioEventId = payload.audioEventId || state.wsProgress.lastAudioEventId;
    updatePendingWindows();
    updateWindow(windowIndex, {
      status: "processing",
      label: `窗口 ${windowIndex} 分析中`,
      audioEventId: payload.audioEventId || null,
      updatedAt: new Date().toISOString()
    });
    setWsHeadline(
      "processing",
      `窗口 ${windowIndex} 已切出并进入分析队列`,
      `累计 ${state.wsProgress.queuedWindows} 个窗口，当前待处理 ${state.wsProgress.pendingWindows} 个。`
    );
    addWsEvent(`窗口 ${windowIndex}`, `audioEvent ${shortId(payload.audioEventId)}`);
    return;
  }

  if (payload.type === "processing_started") {
    state.wsProgress.stopRequested = true;
    state.wsProgress.sessionState = payload.sessionState || "post_processing";
    state.wsProgress.closeReason = payload.closeReason || state.wsProgress.closeReason;
    state.wsProgress.lastAudioEventId = payload.audioEventId || state.wsProgress.lastAudioEventId;
    state.wsProgress.pendingWindows = Number(payload.pendingWindows || state.wsProgress.pendingWindows);
    setWsHeadline(
      "draining",
      "业务会话已结束，进入后处理阶段",
      `当前还有 ${state.wsProgress.pendingWindows} 个窗口排队或处理中。`
    );
    addWsEvent("进入后处理", `等待 ${state.wsProgress.pendingWindows} 个窗口完成`);
    return;
  }

  if (payload.type === "window_processing_completed") {
    const window = findWindowByAudioEventId(payload.audioEventId);
    const windowIndex = window?.windowIndex || state.wsProgress.completedWindows + state.wsProgress.failedWindows + 1;
    state.wsProgress.completedWindows += 1;
    state.wsProgress.lastAudioEventId = payload.audioEventId || state.wsProgress.lastAudioEventId;
    state.wsProgress.lastConversationMemoryId = payload.conversationMemoryId || state.wsProgress.lastConversationMemoryId;
    state.wsProgress.lastProcessingStatus = payload.processingStatus || state.wsProgress.lastProcessingStatus;
    updatePendingWindows();
    updateWindow(windowIndex, {
      status: payload.processingStatus === "failed" ? "failed" : "completed",
      label: `窗口 ${windowIndex} 已完成`,
      audioEventId: payload.audioEventId || null,
      conversationMemoryId: payload.conversationMemoryId || null,
      updatedAt: new Date().toISOString()
    });
    setWsHeadline(
      state.wsProgress.pendingWindows > 0 ? "draining" : "processed",
      `窗口 ${windowIndex} 处理完成`,
      `已完成 ${state.wsProgress.completedWindows} / ${state.wsProgress.queuedWindows} 个窗口。`
    );
    addWsEvent(`完成窗口 ${windowIndex}`, `memory ${shortId(payload.conversationMemoryId)}`);
    return;
  }

  if (payload.type === "processing_completed") {
    state.wsProgress.connected = false;
    state.wsProgress.stopRequested = true;
    state.wsProgress.sessionState = payload.sessionState || "closed";
    state.wsProgress.closeReason = payload.closeReason || state.wsProgress.closeReason;
    state.wsProgress.pendingWindows = 0;
    state.wsProgress.lastAudioEventId = payload.audioEventId || state.wsProgress.lastAudioEventId;
    state.wsProgress.lastConversationMemoryId = payload.conversationMemoryId || state.wsProgress.lastConversationMemoryId;
    state.wsProgress.lastProcessingStatus = payload.processingStatus || state.wsProgress.lastProcessingStatus;
    setWsHeadline(
      payload.processingStatus === "failed" ? "error" : "completed",
      payload.processingStatus === "failed" ? "流式处理失败" : "业务会话与后处理均已完成",
      payload.conversationMemoryId
        ? `最新会话记录 ${shortId(payload.conversationMemoryId)} 已写入。`
        : "可在下方数据视图中查看最新流会话与窗口记录。"
    );
    addWsEvent("会话完成", `audioEvent ${shortId(payload.audioEventId)}`);
    return;
  }

  if (payload.type === "error") {
    state.wsProgress.failedWindows += 1;
    updatePendingWindows();
    setWsHeadline("error", "流式处理出现错误", payload.message || "请检查后端日志或稍后重试。");
    addWsEvent("错误", payload.message || "unknown");
  }
}

function renderWsProgress() {
  const container = $("wsProgress");
  const progress = state.wsProgress;
  if (!container) {
    return;
  }

  if (!progress.connected && !progress.streamSessionId && !progress.windows.length && !progress.events.length) {
    container.className = "stream-progress empty";
    container.innerHTML = `
      <div class="stream-title">
        <strong>窗口进度看板</strong>
        <p>开始流式录音后，这里会实时展示 chunk、窗口排队、窗口处理完成与最终会话写入情况。</p>
      </div>
    `;
    return;
  }

  const phaseClass = progress.phase === "error"
    ? "error"
    : ["processed", "completed"].includes(progress.phase)
      ? "success"
      : progress.stopRequested || progress.pendingWindows > 0
        ? "pending"
        : "";
  const queuedWindows = Math.max(progress.queuedWindows, progress.windows.length);
  const totalResolved = progress.completedWindows + progress.failedWindows;
  const completionRate = queuedWindows > 0 ? Math.min(100, Math.round(totalResolved / queuedWindows * 100)) : 0;
  const successRate = queuedWindows > 0 ? Math.min(100, Math.round(progress.completedWindows / queuedWindows * 100)) : 0;
  const errorRate = queuedWindows > 0 ? Math.min(100, Math.round(progress.failedWindows / queuedWindows * 100)) : 0;
  const timeline = (progress.events || []).map(item => `
      <li>
        <time>${formatClock(item.time)}</time>
        <div>
          <strong>${escapeHtml(item.label)}</strong>
          <div>${escapeHtml(item.detail || "")}</div>
        </div>
      </li>
    `).join("");
  const silenceSeconds = Math.round((progress.silenceMs || 0) / 1000);
  const silenceLimitSeconds = Math.round((progress.silenceTimeoutMs || WS_VAD_SILENCE_TIMEOUT_MS) / 1000);
  const silenceRate = Math.min(100, Math.round((progress.silenceMs || 0) / (progress.silenceTimeoutMs || WS_VAD_SILENCE_TIMEOUT_MS) * 100));
  const transcripts = (progress.transcripts || []).map(item => `
      <article class="stream-transcript">
        <div class="stream-window-top">
          <strong>#${escapeHtml(item.sequence || "--")}</strong>
          <span>${Math.round((item.stability || 0) * 100)}%</span>
        </div>
        <p>${escapeHtml(item.transcript || "")}</p>
      </article>
    `).join("");
  const windows = (progress.windows || []).map(window => {
    const statusLabel = window.status === "completed"
      ? "已完成"
      : window.status === "failed"
        ? "失败"
        : window.status === "processing"
          ? "处理中"
          : "已入队";
    const toneClass = window.status === "completed"
      ? "success"
      : window.status === "failed"
        ? "error"
        : "pending";
    const windowClass = window.status === "completed"
      ? "stream-window is-completed"
      : window.status === "failed"
        ? "stream-window is-failed"
        : window.status === "processing"
          ? "stream-window is-processing"
          : "stream-window";
    return `
      <article class="${windowClass}">
        <div class="stream-window-top">
          <strong>${escapeHtml(window.label || `窗口 ${window.windowIndex}`)}</strong>
          <span class="stream-chip ${toneClass}">${statusLabel}</span>
        </div>
        <div class="stream-window-meta">
          <span>audioEvent ${escapeHtml(shortId(window.audioEventId))}</span>
          <span>${window.conversationMemoryId ? `memory ${escapeHtml(shortId(window.conversationMemoryId))}` : "等待会话写入"}</span>
        </div>
      </article>
    `;
  }).join("");

  container.className = "stream-progress";
  container.innerHTML = `
    <div class="stream-head">
      <div class="stream-title">
        <strong>窗口进度看板</strong>
        <p>${escapeHtml(progress.headline)}</p>
      </div>
      <span class="stream-chip ${phaseClass}">${escapeHtml(progressPhaseLabel(progress))}</span>
    </div>
    <div class="stream-hint">${escapeHtml(progress.detail)}</div>
    <div class="stream-metrics">
      <div class="stream-stat">
        <span>音频片段</span>
        <strong>${progress.chunkCount}</strong>
        <span>${escapeHtml(formatBytes(progress.totalBytes))}</span>
      </div>
      <div class="stream-stat">
        <span>窗口统计</span>
        <strong>${queuedWindows}</strong>
        <span>已完成 ${progress.completedWindows} · 失败 ${progress.failedWindows}</span>
      </div>
      <div class="stream-stat">
        <span>处理中</span>
        <strong>${progress.pendingWindows}</strong>
        <span>${progress.stopRequested ? "停止后排空中" : "采集中可继续切窗"}</span>
      </div>
      <div class="stream-stat">
        <span>会话</span>
        <strong>${escapeHtml(shortId(progress.streamSessionId))}</strong>
        <span>${progress.lastConversationMemoryId ? `memory ${escapeHtml(shortId(progress.lastConversationMemoryId))}` : "尚未产出最终记录"}</span>
      </div>
    </div>
    <div>
      <div class="stream-head">
        <strong>整体进度</strong>
        <span>${completionRate}%</span>
      </div>
      <div class="stream-bar ${phaseClass}"><span style="width:${completionRate}%"></span></div>
    </div>
    <div class="stream-head">
      <strong>成功 / 失败</strong>
      <span>${successRate}% / ${errorRate}%</span>
    </div>
    <div class="stream-metrics">
      <div class="stream-stat">
        <span>成功窗口</span>
        <strong>${progress.completedWindows}</strong>
        <div class="stream-bar success"><span style="width:${successRate}%"></span></div>
      </div>
      <div class="stream-stat">
        <span>失败窗口</span>
        <strong>${progress.failedWindows}</strong>
        <div class="stream-bar error"><span style="width:${errorRate}%"></span></div>
      </div>
      <div class="stream-stat">
        <span>最新 audioEvent</span>
        <strong>${escapeHtml(shortId(progress.lastAudioEventId))}</strong>
        <span>${escapeHtml(progress.lastProcessingStatus || "等待分析")}</span>
      </div>
      <div class="stream-stat">
        <span>静音计时</span>
        <strong>${silenceSeconds}s</strong>
        <div class="stream-bar pending"><span style="width:${silenceRate}%"></span></div>
      </div>
      <div class="stream-stat">
        <span>流状态</span>
        <strong>${escapeHtml(progress.connected ? "OPEN" : "CLOSED")}</strong>
        <span>${progress.closeReason ? escapeHtml(progress.closeReason) : `${silenceLimitSeconds}s 静音阈值`}</span>
      </div>
    </div>
    <section class="stream-transcripts">
      <div class="stream-windows-head">
        <strong>增量转写</strong>
        <span>${(progress.transcripts || []).length} 条</span>
      </div>
      <div class="stream-transcript-grid">
        ${transcripts || `<div class="stream-hint">业务会话开始后展示模型返回的实时片段。</div>`}
      </div>
    </section>
    <section class="stream-windows">
      <div class="stream-windows-head">
        <strong>最近窗口</strong>
        <span>${Math.min(progress.windows.length, queuedWindows)} / ${queuedWindows || progress.windows.length || 0}</span>
      </div>
      <div class="stream-windows-grid">
        ${windows || `<div class="stream-hint">暂无窗口，等待首个 30 秒窗口切出。</div>`}
      </div>
    </section>
    <section>
      <div class="stream-windows-head">
        <strong>实时事件</strong>
        <span>${progress.events.length} 条</span>
      </div>
      <ol class="stream-timeline">
        ${timeline || `<li><time>--:--:--</time><div>等待流会话事件</div></li>`}
      </ol>
    </section>
  `;
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

function insecureRemoteRecordingMessage() {
  return "当前通过局域网 HTTP 地址访问页面，浏览器不会开放麦克风能力。请改用 HTTPS 访问，或在本机通过 localhost / 127.0.0.1 打开。";
}

function getRecordingEnvironmentIssue() {
  if (window.isSecureContext === false) {
    return {
      code: "insecure_context",
      message: insecureRemoteRecordingMessage()
    };
  }
  if (!navigator.mediaDevices) {
    return {
      code: "missing_media_devices",
      message: "当前浏览器环境不支持录音，请使用最新版 Chrome、Edge 或 Safari。"
    };
  }
  if (!navigator.mediaDevices.getUserMedia) {
    return {
      code: "missing_get_user_media",
      message: "当前浏览器不支持麦克风采集，请更换支持 getUserMedia 的浏览器。"
    };
  }
  return null;
}

function friendlyMediaError(error) {
  const envIssue = getRecordingEnvironmentIssue();
  if (envIssue) {
    return envIssue.message;
  }
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
    return insecureRemoteRecordingMessage();
  }
  return error.message || "录音启动失败，请稍后重试。";
}

async function refreshAudioAvailability() {
  const envIssue = getRecordingEnvironmentIssue();
  if (envIssue) {
    setAudioButtonsDisabled(true);
    setAudioStatus(envIssue.message, envIssue.message);
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
  const envIssue = getRecordingEnvironmentIssue();
  if (envIssue) {
    setAudioButtonsDisabled(true);
    setAudioStatus(envIssue.message, envIssue.message);
    throw new Error(envIssue.message);
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
  renderWsProgress();
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
  const correctionToolbar = conversations.length >= 2
    ? `<div class="timeline-actions"><button onclick="mergeRecentConversations()">合并最近两条</button></div>`
    : "";
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
      <div class="timeline-actions">
        <button onclick="reprocessConversation('${item.id}')">重算</button>
        <button onclick="splitConversation('${item.id}')">拆分</button>
      </div>
    </article>`;
  }).join("");
  const healthHtml = health.slice(0, 6).map(item => `<article class="event">
      <div class="meta"><span class="pill amber">health</span><span class="pill">${formatTime(item.measuredAt)}</span></div>
      <h3>${escapeHtml(item.eventType)}</h3>
      <p>${escapeHtml(displayHealthValue(item))}</p>
    </article>`).join("");
  timeline.innerHTML = correctionToolbar + conversationHtml + healthHtml;
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
  resetWsProgress({
    phase: "connecting",
    headline: "正在连接 WebSocket 流会话",
    detail: "建立连接后先进入本地监听态，检测到连续人声后才开始业务会话。"
  });
  state.wsStream = await navigator.mediaDevices.getUserMedia({ audio: true });

  const protocol = window.location.protocol === "https:" ? "wss" : "ws";
  state.ws = new WebSocket(`${protocol}://${window.location.host}/ws/audio?userId=${encodeURIComponent(userId())}`);
  state.ws.binaryType = "arraybuffer";
  state.ws.onopen = () => {
    $("wsStatus").textContent = "WebSocket 已连接，正在监听连续人声";
    state.wsProgress.connected = true;
    state.wsProgress.sessionState = "listening";
    renderWsProgress();
    startWsVadLoop();
    $("wsStartBtn").disabled = true;
    $("wsStopBtn").disabled = false;
  };
  state.ws.onmessage = async event => {
    $("wsStatus").textContent = event.data;
    try {
      const payload = JSON.parse(event.data);
      updateWsProgressFromPayload(payload);
      renderWsProgress();
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
    state.wsProgress.connected = false;
    if (!["completed", "error"].includes(state.wsProgress.phase)) {
      setWsHeadline(
        state.wsProgress.stopRequested ? "draining" : "idle",
        state.wsProgress.stopRequested ? "连接已关闭，等待已入队窗口完成" : "WebSocket 已关闭",
        state.wsProgress.stopRequested ? state.wsProgress.detail : "可以重新开始下一次流式录音。"
      );
    }
    renderWsProgress();
    cleanupWsMedia();
  };
}

function stopWebSocketStream() {
  stopWebSocketSession("manual_stop", "已手动强制结束当前业务会话");
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

async function mergeRecentConversations() {
  const ids = (state.data?.conversationMemories || [])
    .filter(item => !item.discarded)
    .slice(0, 2)
    .map(item => item.id);
  if (ids.length < 2) {
    toast("至少需要两条会话记录");
    return;
  }
  await api("/api/conversations/merge", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ userId: userId(), conversationMemoryIds: ids, reason: "demo_manual_merge" })
  });
  toast("最近两条会话已合并");
  await refresh();
}

async function splitConversation(id) {
  await api(`/api/conversations/${id}/split`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ userId: userId(), reason: "demo_manual_split" })
  });
  toast("会话已拆分");
  await refresh();
}

async function reprocessConversation(id) {
  await api(`/api/conversations/${id}/reprocess`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ userId: userId(), reason: "demo_manual_reprocess" })
  });
  toast("会话已重算");
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

function formatClock(value) {
  if (!value) return "--:--:--";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleTimeString();
}

function formatBytes(value) {
  const bytes = Number(value || 0);
  if (!bytes) {
    return "0 B";
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

function progressPhaseLabel(progress) {
  if (progress.phase === "error") return "异常";
  if (progress.phase === "completed") return "完成";
  if (progress.phase === "processed") return "处理中收尾";
  if (progress.phase === "draining") return "等待排空";
  if (progress.phase === "processing") return "窗口分析中";
  if (progress.phase === "in_session") return "会话中";
  if (progress.phase === "listening") return "监听中";
  if (progress.phase === "capturing") return "采集中";
  if (progress.phase === "streaming") return "流会话已开启";
  if (progress.phase === "connecting") return "连接中";
  return "待开始";
}

function shortId(value) {
  if (!value) {
    return "--";
  }
  const text = String(value);
  return text.length > 8 ? text.slice(0, 8) : text;
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
  setWsHeadline("error", "流式录音启动失败", message);
  addWsEvent("启动失败", message);
  renderWsProgress();
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
    await api("/api/health/events", {
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
    const result = await api("/api/agent/messages", {
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
  .then(() => {
    renderWsProgress();
    return refreshAudioAvailability();
  })
  .catch(error => toast(error.message));

if (navigator.mediaDevices?.addEventListener) {
  navigator.mediaDevices.addEventListener("devicechange", () => {
    refreshAudioAvailability().catch(() => {});
  });
}
