import {useEffect, useRef, useState} from "react";
import type {GenerationEvent, JourneyOutlinePreview} from "../lib/api";

type UseGenerationRunOptions = {
  runId: string | null;
  eventsUrl: (runId: string) => string;
  cancel: (runId: string) => Promise<unknown>;
};

export function useGenerationRun({runId, eventsUrl, cancel}: UseGenerationRunOptions) {
  const [events, setEvents] = useState<GenerationEvent[]>([]);
  const [connection, setConnection] = useState<"connected" | "reconnecting">("reconnecting");
  const [elapsedMs, setElapsedMs] = useState(0);
  const startedAt = useRef<number | null>(null);
  const terminal = useRef(false);

  useEffect(() => {
    setEvents([]);
    setElapsedMs(0);
    startedAt.current = null;
    terminal.current = false;
    if (!runId) {
      setConnection("reconnecting");
      return;
    }

    const source = new EventSource(eventsUrl(runId));
    const timer = window.setInterval(() => {
      if (startedAt.current !== null && !terminal.current) setElapsedMs(Date.now() - startedAt.current);
    }, 1000);
    source.onopen = () => setConnection("connected");
    source.onmessage = (message) => {
      try {
        const next = JSON.parse(message.data) as GenerationEvent;
        startedAt.current ??= Date.parse(next.timestamp) || Date.now();
        setElapsedMs(Math.max(0, Date.now() - startedAt.current));
        setEvents((current) => current.some((event) => event.sequence === next.sequence)
          ? current : [...current, next].sort((left, right) => left.sequence - right.sequence));
        if (next.status !== "RUNNING") {
          terminal.current = true;
          source.close();
        }
      } catch {
        setConnection("reconnecting");
      }
    };
    source.onerror = () => {
      if (terminal.current) source.close();
      else setConnection("reconnecting");
    };
    return () => {
      window.clearInterval(timer);
      source.close();
    };
  }, [eventsUrl, runId]);

  const latest = events.at(-1);
  const preview = [...events].reverse().find((event) => event.preview !== null)?.preview ?? null;

  return {
    events,
    status: latest?.status ?? "RUNNING",
    stage: latest?.stage ?? "PREPARING",
    preview: preview as JourneyOutlinePreview | null,
    elapsedMs,
    connection,
    cancel: () => runId ? cancel(runId) : Promise.resolve(),
  };
}
