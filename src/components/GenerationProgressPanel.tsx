import type {GenerationEvent} from "../lib/api";

type GenerationProgressPanelProps = {
  operation: string;
  events: GenerationEvent[];
  status: GenerationEvent["status"];
  stage: string;
  elapsedMs: number;
  connection: "connected" | "reconnecting";
  onCancel: () => void | Promise<unknown>;
};

function statusLabel(status: GenerationEvent["status"]) {
  return status === "RUNNING" ? "处理中" : status === "COMPLETED" ? "已完成" : status === "FAILED" ? "失败" : "已取消";
}

function stageLabel(stage: string) {
  return {
    PREPARING: "准备上下文",
    CALLING_MODEL: "调用大模型",
    VALIDATING: "校验结果",
    WAITING_CONFIRMATION: "等待确认",
    PERSISTING: "保存数据",
  }[stage] ?? stage;
}

function elapsedLabel(elapsedMs: number) {
  const seconds = Math.floor(elapsedMs / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

export function GenerationProgressPanel({
  operation,
  events,
  status,
  stage,
  elapsedMs,
  connection,
  onCancel,
}: GenerationProgressPanelProps) {
  const latest = events.at(-1);
  const terminal = status !== "RUNNING";
  return (
    <section className="generation-progress" aria-live="polite" aria-label={`${operation}生成进度`}>
      <div className="generation-progress-header">
        <div>
          <div className="section-kicker">GENERATION RUN · {operation}</div>
          <strong>{latest?.content ?? "正在连接 Agent…"}</strong>
        </div>
        <span className="status-pill">{statusLabel(status)}</span>
      </div>
      <div className="generation-progress-meta">
        <span>阶段：{stageLabel(stage)}</span>
        <span>已用时：{elapsedLabel(elapsedMs)}</span>
        <span>{connection === "connected" ? "事件流已连接" : "事件流重连中"}</span>
        {!terminal && <button className="secondary" type="button" onClick={() => void onCancel()}>取消生成</button>}
      </div>
    </section>
  );
}
