/*
 * Copyright © 2018 by elfuego.biz
 */
package biz.elfuego.idea.issues.gitea;

import biz.elfuego.idea.issues.gitea.model.GiteaProject;
import biz.elfuego.idea.issues.gitea.model.GiteaTask;
import biz.elfuego.idea.issues.gitea.util.Consts;
import biz.elfuego.idea.issues.gitea.util.Consts.CommentFields;
import biz.elfuego.idea.issues.gitea.util.Consts.ProjectFilter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.tasks.Comment;
import com.intellij.tasks.CustomTaskState;
import com.intellij.tasks.Task;
import com.intellij.tasks.impl.BaseRepositoryImpl;
import com.intellij.tasks.impl.SimpleComment;
import com.intellij.util.xmlb.annotations.AbstractCollection;
import com.intellij.util.xmlb.annotations.Tag;
import com.intellij.util.xmlb.annotations.Transient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.auth.AuthPolicy;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static biz.elfuego.idea.issues.gitea.util.Utils.*;

/**
 * @author Roman Pedchenko <elfuego@elfuego.biz>
 * @date 2018.06.30
 */
@Tag("Gitea")
class GiteaRepository extends BaseRepositoryImpl {
    private static final Logger logger = Logger.getInstance(GiteaRepository.class);

    private String userId = null;
    private String userLogin = null;
    private ProjectFilter projectFilter = ProjectFilter.GENERAL;
    private List<GiteaProject> projects = new ArrayList<>();
    private GiteaProject selectedProject = null;

    @SuppressWarnings("UnusedDeclaration")
    public GiteaRepository() {
        super();
    }

    @SuppressWarnings({"UnusedDeclaration", "WeakerAccess"})
    public GiteaRepository(GiteaRepositoryType type) {
        super(type);
        setUseHttpAuthentication(true);
        setUrl(Consts.Url.DEFAULT);
    }

    @SuppressWarnings({"UnusedDeclaration", "WeakerAccess"})
    public GiteaRepository(GiteaRepository other) {
        super(other);
        userId = other.userId;
        userLogin = other.userLogin;
        projectFilter = other.projectFilter;
        projects = other.projects;
        selectedProject = other.selectedProject;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof GiteaRepository))
            return false;
        GiteaRepository other = (GiteaRepository) o;
        return equal(userId, other.userId) &&
                equal(userLogin, other.userLogin) &&
                equal(projectFilter, other.projectFilter) &&
                equal(projects, other.projects) &&
                equal(selectedProject, other.selectedProject);
    }

    private boolean equal(Object o1, Object o2) {
        return o1 == null && o2 == null || o1 != null && o1.equals(o2);
    }

    @Nullable
    @Override
    public Task findTask(@NotNull String s) /*throws Exception*/ {
        // TODO
        return null;
    }

    @Override
    public Task[] getIssues(@Nullable String query, int offset, int limit, boolean withClosed, @NotNull ProgressIndicator cancelled) throws Exception {
        return getIssues();
    }

    @NotNull
    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public GiteaRepository clone() {
        return new GiteaRepository(this);
    }

    @Nullable
    @Override
    public CancellableConnection createCancellableConnection() {
        return new CancellableConnection() {
            @Override
            protected void doTest() throws Exception {
                GiteaRepository.this.doTest();
            }

            @Override
            public void cancel() {
                //Jetbrains left this method blank in their generic task repo as well. Just let it time out?
            }
        };
    }

    @Nullable
    @Override
    public String extractId(@NotNull String taskName) {
        Matcher matcher = Pattern.compile("(d+)").matcher(taskName);
        return matcher.find() ? matcher.group(1) : null;
    }

    @NotNull
    @Override
    public Set<CustomTaskState> getAvailableTaskStates(@NotNull Task task) {
        Set<CustomTaskState> result = new HashSet<>();
        for (Consts.States state : Consts.States.values()) {
            String name = state.name().toLowerCase();
            result.add(new CustomTaskState(name, name));
        }
        return result;
    }

    @Override
    public void setTaskState(@NotNull Task task, @NotNull CustomTaskState state) throws Exception {
        GiteaTaskImpl giteaTask = null;
        if (task instanceof GiteaTaskImpl) {
            giteaTask = (GiteaTaskImpl) task;
        } else {
            Task[] tasks = getIssues();
            for (Task t : tasks) {
                if (task.getId().equals(t.getId())) {
                    giteaTask = (GiteaTaskImpl) t;
                }
            }
        }

        if (giteaTask == null) {
            throw new Exception("Task not found");
        }

        giteaTask.task.setState(state.getId());
        JsonObject jsonData = new JsonObject();
        jsonData.addProperty("state", state.getId());
        StringRequestEntity data = new StringRequestEntity(
                jsonData.toString(),
                "application/json",
                "UTF-8"
        );
        HttpMethod patchTask = getPatchMethod(getApiUrl() + Consts.EndPoint.REPOS + selectedProject.getName()
                + Consts.EndPoint.ISSUES + "/" + giteaTask.getId(), data);
        executeMethod(patchTask);
    }

    @Override
    protected int getFeatures() {
        return NATIVE_SEARCH | STATE_UPDATING;
    }

    private void doTest() throws Exception {
        userId = null;
        userLogin = null;
        checkSetup();
        GetMethod method = new GetMethod(getApiUrl() + Consts.EndPoint.ME);
        JsonElement response = executeMethod(method);
        if (response == null)
            throw new Exception(String.format("%s: %d, %s", Consts.ERROR, method.getStatusCode(), method.getStatusText()));
        final JsonObject obj = getObject(response);
        if (obj.has("id") && obj.has("login"))
            return;
        throw new Exception(Consts.ERROR);
    }

    @NotNull
    private String getApiUrl() {
        return getUrl() + Consts.EndPoint.API;
    }

    @Override
    public boolean isConfigured() {
        boolean result = true;
        if (!super.isConfigured()) {
            result = false;
        }
        if (result && StringUtil.isEmpty(this.getUrl())) {
            result = false;
        }
        if (result && StringUtil.isEmpty(this.getUsername())) {
            result = false;
        }
        if (result && StringUtil.isEmpty(this.getPassword())) {
            result = false;
        }
        return result;
    }

    private void checkSetup() throws Exception {
        String result = "";
        int errors = 0;
        if (StringUtil.isEmpty(getUrl())) {
            result += "Server";
            errors++;
        }
        if (StringUtil.isEmpty(getUsername())) {
            result += !StringUtils.isEmpty(result) ? " & " : "";
            result += "Username";
            errors++;
        }
        if (StringUtil.isEmpty(getPassword())) {
            result += !StringUtils.isEmpty(result) ? " & " : "";
            result += "Password";
            errors++;
        }
        if (!result.isEmpty()) {
            throw new Exception(result + ((errors > 1) ? " are required" : " is required"));
        }
    }

    private Task[] getIssues() throws Exception {
        if (ifNoSelectedProj())
            return new Task[]{};
        if (!ensureUserId())
            return new Task[]{};
        List<GiteaTaskImpl> result = new ArrayList<>();

        final String url = getApiUrl() + Consts.EndPoint.REPOS + selectedProject.getName() + Consts.EndPoint.ISSUES;
        final JsonElement response = executeMethod(new GetMethod(url));
        if (response == null)
            return new Task[]{};
        JsonArray tasks = getArray(response);
        for (int i = 0; i < tasks.size(); i++) {
            JsonObject current = tasks.get(i).getAsJsonObject();
            GiteaTask raw = new GiteaTask(selectedProject, current);
            if (!raw.isValid()) {
                continue;
            }
            GiteaTaskImpl mapped = new GiteaTaskImpl(this, raw);
            result.add(mapped);
        }
        Collections.sort(result);
        Task[] primArray = new Task[result.size()];
        return result.toArray(primArray);
    }

    private boolean ifNoSelectedProj() {
        return selectedProject == null || selectedProject.getId().equals("-1");
    }

    Comment[] getComments(GiteaTaskImpl task) throws Exception {
        if (ifNoSelectedProj())
            return new Comment[]{};
        if (!ensureUserId())
            return new Comment[]{};
        List<SimpleComment> result = new ArrayList<>();

        final String url = getApiUrl() + Consts.EndPoint.REPOS + selectedProject.getName() + Consts.EndPoint.ISSUES
                + "/" + task.getId() + Consts.EndPoint.COMMENTS;
        final JsonElement response = executeMethod(new GetMethod(url));
        if (response == null)
            return new Comment[]{};
        JsonArray comments = getArray(response);
        for (int i = 0; i < comments.size(); i++) {
            JsonObject current = comments.get(i).getAsJsonObject();
            Date date = getDate(current, CommentFields.DATE);
            String text = getString(current, CommentFields.TEXT, "");
            JsonObject user = getObject(current, CommentFields.USER);
            String author = getString(user, CommentFields.FULLNAME, "");
            if (author.isEmpty())
                author = getString(user, CommentFields.USERNAME, "");
            result.add(new SimpleComment(date, author, text));
        }
        Comment[] primArray = new Comment[result.size()];
        return result.toArray(primArray);
    }

    private JsonElement executeMethod(@NotNull HttpMethod method) throws Exception {
        method.addRequestHeader("Content-type", "application/json");
        List authPrefs = Collections.singletonList(AuthPolicy.BASIC);
        method.getParams().setParameter(AuthPolicy.AUTH_SCHEME_PRIORITY, authPrefs);
        getHttpClient().executeMethod(method);

        if (method.getStatusCode() != HttpStatus.SC_OK && method.getStatusCode() != HttpStatus.SC_CREATED) {
            logger.warn(String.format("HTTP error: %d, %s", method.getStatusCode(), method.getStatusText()));
            return null;
        }

        return new JsonParser().parse(new InputStreamReader(method.getResponseBodyAsStream(), StandardCharsets.UTF_8));
    }

    private HttpMethod getPatchMethod(String url, StringRequestEntity data) {
        PostMethod patchMethod = new PostMethod(url) {
            @Override
            public String getName() {
                return "PATCH";
            }
        };
        patchMethod.setRequestEntity(data);
        return patchMethod;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean ensureUserId() throws Exception {
        if (userLogin == null || userLogin.isEmpty()) {
            JsonElement result = executeMethod(new GetMethod(getApiUrl() + Consts.EndPoint.ME));
            if (result == null)
                return false;
            userId = result.getAsJsonObject().get("id").getAsJsonPrimitive().getAsString();
            userLogin = result.getAsJsonObject().get("login").getAsJsonPrimitive().getAsString();
        }
        return true;
    }

    @Transient
    List<GiteaProject> getProjectList(ProjectFilter projectFilter) throws Exception {
        if (!ensureUserId())
            return Collections.emptyList();
        final String query;
        if (projectFilter == null)
            projectFilter = ProjectFilter.GENERAL;
        switch (projectFilter) {
            case CONTRUBUTOR:
                query = Consts.EndPoint.REPOS_SEARCH_UID + userId;
                break;
            case OWNER:
                query = Consts.EndPoint.REPOS_SEARCH_UID_EX + userId;
                break;
            default:
                query = Consts.EndPoint.REPOS_SEARCH;
                break;
        }
        JsonElement response = executeMethod(new GetMethod(getApiUrl() + query));
        if (response == null)
            return Collections.emptyList();
        JsonArray reply = getOkData(response);
        List<GiteaProject> result = new ArrayList<>();
        for (int i = 0; i < reply.size(); i++) {
            JsonObject current = getObject(reply.get(i));
            GiteaProject project = new GiteaProject().setId(getString(current, "id", ""))
                    .setName(getString(current, "full_name", ""));
            if (!project.isValid()) {
                continue;
            }
            result.add(project);
        }
        projects = result;
        return projects;
    }

    public ProjectFilter getProjectFilter() {
        return projectFilter;
    }

    public void setProjectFilter(ProjectFilter projectFilter) {
        this.projectFilter = projectFilter;
    }

    public GiteaProject getSelectedProject() {
        return selectedProject;
    }

    public void setSelectedProject(GiteaProject selectedProject) {
        this.selectedProject = selectedProject;
    }

    @SuppressWarnings("UnusedDeclaration")
    public String getUserId() {
        return userId;
    }

    @SuppressWarnings("UnusedDeclaration")
    public void setUserId(String userId) {
        this.userId = userId;
    }

    @SuppressWarnings("UnusedDeclaration")
    public String getUserLogin() {
        return userLogin;
    }

    @SuppressWarnings("UnusedDeclaration")
    public void setUserLogin(String userLogin) {
        this.userLogin = userLogin;
    }

    @SuppressWarnings("UnusedDeclaration")
    @AbstractCollection(surroundWithTag = false, elementTag = "GiteaProject", elementTypes = GiteaProject.class)
    public List<GiteaProject> getProjects() {
        return projects;
    }

    @SuppressWarnings("UnusedDeclaration")
    public void setProjects(List<GiteaProject> projects) {
        this.projects = projects;
    }
}
