/*
 * Copyright © 2018 by elfuego.biz
 */
package biz.elfuego.idea.issues.gitea;

import biz.elfuego.idea.issues.gitea.model.GiteaTask;
import biz.elfuego.idea.issues.gitea.util.Consts;
import biz.elfuego.idea.issues.gitea.util.Consts.CommentFields;
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
import com.intellij.util.xmlb.annotations.Tag;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpStatus;
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
    private String repoName = null;
    private String projName = null;
    private String token = null;

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
        repoName = other.repoName;
        projName = other.projName;
        token = other.token;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof GiteaRepository))
            return false;
        GiteaRepository other = (GiteaRepository) o;
        return equal(userId, other.userId) &&
                equal(userLogin, other.userLogin) &&
                equal(repoName, other.repoName) &&
                equal(projName, other.projName) &&
                equal(token, other.token);
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
        HttpMethod patchTask = getPatchMethod(getApiUrl() + Consts.EndPoint.REPOS + getProject()
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
        if (result && StringUtil.isEmpty(this.getRepoName())) {
            result = false;
        }
        if (result && StringUtil.isEmpty(this.getProjName())) {
            result = false;
        }
        if (result && StringUtil.isEmpty(this.getToken())) {
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
        if (StringUtil.isEmpty(getRepoName())) {
            result += !StringUtils.isEmpty(result) ? " & " : "";
            result += "Repo name";
            errors++;
        }
        if (StringUtil.isEmpty(getProjName())) {
            result += !StringUtils.isEmpty(result) ? " & " : "";
            result += "Project name";
            errors++;
        }
        if (StringUtil.isEmpty(getToken())) {
            result += !StringUtils.isEmpty(result) ? " & " : "";
            result += "Token";
            errors++;
        }
        if (!result.isEmpty()) {
            throw new Exception(result + ((errors > 1) ? " are required" : " is required"));
        }
    }

    private Task[] getIssues() throws Exception {
        if (!ensureUserId())
            return new Task[]{};
        List<GiteaTaskImpl> result = new ArrayList<>();

        final String url = getApiUrl() + Consts.EndPoint.REPOS + getProject() + Consts.EndPoint.ISSUES;
        final JsonElement response = executeMethod(new GetMethod(url));
        if (response == null)
            return new Task[]{};
        JsonArray tasks = getArray(response);
        for (int i = 0; i < tasks.size(); i++) {
            JsonObject current = tasks.get(i).getAsJsonObject();
            GiteaTask raw = new GiteaTask(getProject(), current);
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

    private String getProject() {
        return String.format("%s/%s", repoName, projName);
    }

    Comment[] getComments(GiteaTaskImpl task) throws Exception {
        if (!ensureUserId())
            return new Comment[]{};
        List<SimpleComment> result = new ArrayList<>();

        final String url = getApiUrl() + Consts.EndPoint.REPOS + getProject() + Consts.EndPoint.ISSUES
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
        method.addRequestHeader("Authorization", "token " + token);
        method.addRequestHeader("Content-type", "application/json");
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

    @SuppressWarnings("WeakerAccess")
    public String getRepoName() {
        return repoName;
    }

    public void setRepoName(String repoName) {
        this.repoName = repoName;
    }

    @SuppressWarnings("WeakerAccess")
    public String getProjName() {
        return projName;
    }

    public void setProjName(String projName) {
        this.projName = projName;
    }

    @SuppressWarnings("WeakerAccess")
    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
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
}
