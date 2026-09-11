import type {JourneyDraftEvent} from "./api";

export function mergeModelDeltas(events: JourneyDraftEvent[]) {
  return events.reduce<JourneyDraftEvent[]>((merged, event) => {
    const previous = merged.at(-1);
    if (event.eventType === "model_delta" && previous?.eventType === "model_delta"
      && previous.runId === event.runId && previous.author === event.author) {
      merged[merged.length - 1] = {...previous, content: previous.content + event.content};
    } else {
      merged.push(event);
    }
    return merged;
  }, []);
}
