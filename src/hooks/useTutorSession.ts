import type {SyntheticEvent} from "react";
import {useEffect, useRef, useState} from "react";
import {api, type LearnUnitResponse, type Message, type SessionDetail, type TutorEvent,} from "../lib/api";

type UseTutorSessionOptions = {
    journeyId: string | undefined;
    learnUnit: LearnUnitResponse | null;
    setBusy: (busy: boolean) => void;
    setError: (error: string | null) => void;
    errorMessage: (cause: unknown, fallback: string) => string;
};

export function useTutorSession({
                                    journeyId,
                                    learnUnit,
                                    setBusy,
                                    setError,
                                    errorMessage,
                                }: UseTutorSessionOptions) {
    const [tutor, setTutor] = useState<SessionDetail | null>(null);
    const [tutorInput, setTutorInput] = useState("");
    const [events, setEvents] = useState<TutorEvent[]>([]);
    const [activeTutorRunId, setActiveTutorRunId] = useState<string | null>(null);
    const eventSource = useRef<EventSource | null>(null);
    const terminalRuns = useRef(new Set<string>());
    const runtimeEpoch = useRef(0);

    useEffect(() => {
        eventSource.current?.close();
        if (!tutor) {
            setEvents([]);
            setActiveTutorRunId(null);
            terminalRuns.current.clear();
            return;
        }
        const epoch = runtimeEpoch.current;
        const source = new EventSource(api.eventsUrl(tutor.id));
        eventSource.current = source;
        source.onmessage = (event) => {
            if (epoch !== runtimeEpoch.current) return;
            const next = JSON.parse(event.data) as TutorEvent;
            setEvents((current) => current.some((item) => item.id === next.id) ? current : [...current, next]);
            if (next.eventType === "complete" || next.eventType === "error" || next.eventType === "cancelled") {
                terminalRuns.current.add(next.runId);
                setActiveTutorRunId((current) => current === next.runId ? null : current);
            }
            if (next.eventType === "complete") {
                void api.session(tutor.id).then((session) => {
                    if (epoch === runtimeEpoch.current) setTutor(session);
                }).catch(() => undefined);
            }
        };
        return () => {
            source.close();
            if (eventSource.current === source) eventSource.current = null;
        };
    }, [tutor?.id]);

    async function openTutor() {
        if (!journeyId || !learnUnit) return;
        setBusy(true);
        setError(null);
        try {
            const linked = await api.tutor(journeyId, learnUnit.learnUnit.code);
            setTutor(await api.session(linked.session.id));
        } catch (cause) {
            setError(errorMessage(cause, "Unable to open tutor"));
        } finally {
            setBusy(false);
        }
    }

    async function sendTutorMessage(event: SyntheticEvent<HTMLFormElement>) {
        event.preventDefault();
        const content = tutorInput.trim();
        if (!tutor || !content) return;
        setTutorInput("");
        const message: Message = {
            id: `local-${Date.now()}`,
            sessionId: tutor.id,
            role: "user",
            content,
            createdAt: new Date().toISOString(),
        };
        setTutor((current) => current && {...current, messages: [...current.messages, message]});
        try {
            const sent = await api.sendMessage(tutor.id, content);
            if (!terminalRuns.current.has(sent.runId)) setActiveTutorRunId(sent.runId);
        } catch (cause) {
            setError(errorMessage(cause, "Unable to send tutor message"));
        }
    }

    async function cancelTutorRun() {
        if (!tutor || !activeTutorRunId) return;
        try {
            await api.cancelRun(tutor.id, activeTutorRunId);
            setActiveTutorRunId(null);
        } catch (cause) {
            setError(errorMessage(cause, "Unable to cancel tutor message"));
        }
    }

    function closeTutor() {
        setTutor(null);
        setActiveTutorRunId(null);
    }

    function resetTutor() {
        runtimeEpoch.current += 1;
        eventSource.current?.close();
        eventSource.current = null;
        terminalRuns.current.clear();
        setTutor(null);
        setTutorInput("");
        setEvents([]);
        setActiveTutorRunId(null);
    }

    return {
        tutor,
        tutorInput,
        events,
        activeTutorRunId,
        openTutor,
        sendTutorMessage,
        cancelTutorRun,
        closeTutor,
        resetTutor,
        setTutorInput,
    };
}
