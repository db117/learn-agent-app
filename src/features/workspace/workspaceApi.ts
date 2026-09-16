export type WorkspaceFileEntry = {
    path: string;
    size: number;
    modifiedAt: string;
};

export type WorkspaceFile = WorkspaceFileEntry & { content: string };

export type WorkspaceApi = ReturnType<typeof createWorkspaceApi>;

type FetchLike = typeof fetch;

export function createWorkspaceApi(fetcher: FetchLike = fetch, baseUrl = "") {
    const request = async <T>(path: string, init?: RequestInit): Promise<T> => {
        const response = await fetcher(`${baseUrl}${path}`, init);
        if (!response.ok) throw new Error("Workspace 请求失败");
        return response.json() as Promise<T>;
    };

    const filePath = (path: string) => path.split("/").map(encodeURIComponent).join("/");
    const journeyBase = (journeyId: number) => `/api/journeys/${journeyId}/workspace/files`;
    const projectBase = (projectId: number) => `/api/projects/${projectId}/workspace/files`;

    return {
        listFiles: (scope: "journey" | "project", id: number) =>
            request<WorkspaceFileEntry[]>(scope === "journey" ? journeyBase(id) : projectBase(id)),
        readFile: (scope: "journey" | "project", id: number, path: string) =>
            request<WorkspaceFile>(`${scope === "journey" ? journeyBase(id) : projectBase(id)}/${filePath(path)}`),
        writeFile: (scope: "journey" | "project", id: number, path: string, content: string) =>
            request<WorkspaceFile>(`${scope === "journey" ? journeyBase(id) : projectBase(id)}/${filePath(path)}`, {
                method: "PUT",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({content}),
            }),
    };
}
