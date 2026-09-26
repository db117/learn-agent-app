package com.db117.learnagent.workspace.api;

import com.db117.learnagent.workspace.application.WorkspaceApplicationService;
import com.db117.learnagent.workspace.application.WorkspaceInitializationException;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import com.db117.learnagent.workspace.domain.Workspace;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;

/** Workspace 文件 API；文件内容始终由后端本地 Workspace 提供。 */
@Path("/api")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WorkspaceResource {
    private final WorkspaceApplicationService access;
    private final WorkspaceManager workspaces;

    @Inject
    public WorkspaceResource(
            WorkspaceApplicationService access,
            WorkspaceManager workspaces) {
        this.access = access;
        this.workspaces = workspaces;
    }

    WorkspaceResource(WorkspaceManager workspaces) {
        this(null, workspaces);
    }

    @GET
    @Path("/journeys/{journeyId}/workspace/files")
    public List<WorkspaceFileEntryResponse> listLearningFiles(@PathParam("journeyId") long journeyId) {
        return list(learningWorkspace(journeyId));
    }

    /** 返回归属校验后的本地 Workspace 路径，供桌面端打开 IDE 或复制路径。 */
    @GET
    @Path("/journeys/{journeyId}/workspace/path")
    @Produces(MediaType.TEXT_PLAIN)
    public String learningWorkspacePath(@PathParam("journeyId") long journeyId) {
        return learningWorkspace(journeyId).root().toString();
    }

    @GET
    @Path("/journeys/{journeyId}/workspace/files/{path: .+}")
    public WorkspaceFileResponse readLearningFile(@PathParam("journeyId") long journeyId,
                                                  @PathParam("path") String path) {
        return read(learningWorkspace(journeyId), path);
    }

    @PUT
    @Path("/journeys/{journeyId}/workspace/files/{path: .+}")
    public WorkspaceFileResponse writeLearningFile(@PathParam("journeyId") long journeyId,
                                                   @PathParam("path") String path,
                                                   WorkspaceContentRequest request) {
        return write(learningWorkspace(journeyId), path, request);
    }

    @GET
    @Path("/projects/{projectId}/workspace/files")
    public List<WorkspaceFileEntryResponse> listProjectFiles(@PathParam("projectId") long projectId) {
        return list(projectWorkspace(projectId));
    }

    @GET
    @Path("/projects/{projectId}/workspace/files/{path: .+}")
    public WorkspaceFileResponse readProjectFile(@PathParam("projectId") long projectId,
                                                 @PathParam("path") String path) {
        return read(projectWorkspace(projectId), path);
    }

    @PUT
    @Path("/projects/{projectId}/workspace/files/{path: .+}")
    public WorkspaceFileResponse writeProjectFile(@PathParam("projectId") long projectId,
                                                  @PathParam("path") String path,
                                                  WorkspaceContentRequest request) {
        return write(projectWorkspace(projectId), path, request);
    }

    private Workspace learningWorkspace(long journeyId) {
        try {
            return access == null ? workspaces.learningWorkspace(journeyId) : access.learningWorkspace(journeyId);
        } catch (WorkspaceInitializationException error) {
            throw WorkspaceRequestException.internal(error);
        }
    }

    private Workspace projectWorkspace(long projectId) {
        try {
            return access == null ? workspaces.projectWorkspace(projectId) : access.projectWorkspace(projectId);
        } catch (WorkspaceInitializationException error) {
            throw WorkspaceRequestException.internal(error);
        }
    }

    private List<WorkspaceFileEntryResponse> list(Workspace workspace) {
        try {
            return workspaces.listFiles(workspace).stream().map(WorkspaceFileEntryResponse::from).toList();
        } catch (IllegalArgumentException error) {
            throw WorkspaceRequestException.badRequest(error);
        } catch (NoSuchFileException error) {
            throw WorkspaceRequestException.notFound("Workspace 不存在", error);
        } catch (IOException error) {
            throw WorkspaceRequestException.internal(error);
        }
    }

    private WorkspaceFileResponse read(Workspace workspace, String path) {
        try {
            return WorkspaceFileResponse.from(workspaces.readFile(workspace, path));
        } catch (IllegalArgumentException error) {
            throw WorkspaceRequestException.badRequest(error);
        } catch (NoSuchFileException error) {
            throw WorkspaceRequestException.notFound("Workspace 文件不存在", error);
        } catch (IOException error) {
            throw WorkspaceRequestException.internal(error);
        }
    }

    private WorkspaceFileResponse write(Workspace workspace, String path, WorkspaceContentRequest request) {
        if (request == null || request.content() == null) {
            throw WorkspaceRequestException.badRequest(
                    new IllegalArgumentException("content must not be null"));
        }
        try {
            return WorkspaceFileResponse.from(workspaces.writeFile(workspace, path, request.content()));
        } catch (IllegalArgumentException error) {
            throw WorkspaceRequestException.badRequest(error);
        } catch (IOException error) {
            throw WorkspaceRequestException.internal(error);
        }
    }
}
