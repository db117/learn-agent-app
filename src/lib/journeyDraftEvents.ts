import type {GenerationEvent} from "./api";

export function mergeModelDeltas(events: GenerationEvent[]) {
  return events.reduce<GenerationEvent[]>((merged, event) => {
    const previous = merged.at(-1);
    if (event.eventType === "model_preview" && previous?.eventType === "model_preview"
      && previous.runId === event.runId && previous.author === event.author) {
      merged[merged.length - 1] = {...previous, content: previous.content + event.content};
    } else {
      merged.push(event);
    }
    return merged;
  }, []);
}
