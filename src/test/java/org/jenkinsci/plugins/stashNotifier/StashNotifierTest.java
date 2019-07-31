package org.jenkinsci.plugins.stashNotifier;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.ProxyConfiguration;
import hudson.model.*;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.Build;
import hudson.plugins.git.util.BuildData;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.acegisecurity.Authentication;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.StatusLine;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.AuthenticationException;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthenticationStrategy;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.*;

@RunWith(PowerMockRunner.class)
@PrepareForTest({Secret.class, Jenkins.class, HttpClientBuilder.class, TokenMacro.class, CredentialsMatchers.class, com.cloudbees.plugins.credentials.CredentialsProvider.class, AbstractProject.class})
@PowerMockIgnore("javax.net.ssl.*")
public class StashNotifierTest {

    final static String sha1 = "1234567890123456789012345678901234567890";
    private HttpClientBuilder httpClientBuilder;
    private CloseableHttpClient client;
    private Jenkins jenkins;

    public StashNotifier buildStashNotifier(String stashBaseUrl) {
        return buildStashNotifier(stashBaseUrl, false, false);
    }

    public StashNotifier buildStashNotifier(String stashBaseUrl,
                                            boolean disableInprogressNotification,
                                            boolean considerUnstableAsSuccess) {
        return new StashNotifier(
                stashBaseUrl,
                "scot",
                true,
                null,
                true,
                "test-project",
                true,
                disableInprogressNotification,
                considerUnstableAsSuccess,
                new JenkinsLocationConfiguration()
        );
    }

    StashNotifier sn;
    BuildListener buildListener;
    AbstractBuild<?, ?> build;
    Run<?, ?> run;
    FilePath workspace;

    @Before
    public void setUp() throws IOException, InterruptedException, MacroEvaluationException {
        PowerMockito.mockStatic(Secret.class);
        PowerMockito.mockStatic(Jenkins.class);
        PowerMockito.mockStatic(HttpClientBuilder.class);
        PowerMockito.mockStatic(TokenMacro.class);
        PowerMockito.mockStatic(com.cloudbees.plugins.credentials.CredentialsProvider.class);

        buildListener = mock(BuildListener.class);
        jenkins = mock(Jenkins.class);
        build = mock(AbstractBuild.class);
        run = mock(Run.class);
        AbstractProject project = PowerMockito.mock(AbstractProject.class);
        File file = mock(File.class);
        when(file.getPath()).thenReturn("/tmp/fake/path");
        FilePath filePath = new FilePath(file);
        PowerMockito.when(project.getSomeWorkspace()).thenReturn(filePath);
        workspace = project.getSomeWorkspace();
        EnvVars environment = mock(EnvVars.class);
        PrintStream logger = System.out;
        Secret secret = mock(Secret.class);
        httpClientBuilder = PowerMockito.mock(HttpClientBuilder.class);
        client = mock(CloseableHttpClient.class);
        CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
        HttpUriRequest req = mock(HttpUriRequest.class);
        StatusLine statusLine = mock(StatusLine.class);
        BuildData action = mock(BuildData.class);
        Revision revision = mock(Revision.class);
        Build lastBuild = mock(Build.class);
        List<BuildData> actions = Collections.singletonList(action);

        when(Jenkins.getInstance()).thenReturn(jenkins);
        when(jenkins.getRootUrl()).thenReturn("http://localhost/");
        when(build.getEnvironment(buildListener)).thenReturn(environment);
        when(action.getLastBuiltRevision()).thenReturn(revision);
        when(revision.getSha1String()).thenReturn(sha1);
        doReturn(project).when(build).getProject();
        doReturn(project).when(run).getParent();
        when(build.getFullDisplayName()).thenReturn("foo");
        when(build.getUrl()).thenReturn("foo");
        when(build.getActions(BuildData.class)).thenReturn(actions);
        when(environment.expand(anyString())).thenReturn(sha1);
        when(buildListener.getLogger()).thenReturn(logger);
        when(Secret.fromString("tiger")).thenReturn(secret);
        when(Secret.toString(secret)).thenReturn("tiger");
        when(secret.getPlainText()).thenReturn("tiger");
        when(HttpClientBuilder.create()).thenReturn(httpClientBuilder);
        when(httpClientBuilder.build()).thenReturn(client);
        when(client.execute((HttpUriRequest) anyObject())).thenReturn(resp);
        when(resp.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(204);
        action.lastBuild = lastBuild;
        when(lastBuild.getMarked()).thenReturn(revision);


        when(TokenMacro.expandAll(build, buildListener, "test-project")).thenReturn("prepend-key");
        when(com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
                (Class) anyObject(),
                (ItemGroup) anyObject(),
                (Authentication) anyObject(),
                (List<DomainRequirement>) anyList()
        )).thenReturn(new ArrayList<Credentials>());

        sn = buildStashNotifier("http://localhost");
    }

    @Test
    public void test_prebuild_normal() {
        assertTrue(sn.prebuild(build, buildListener));
    }

    @Test
    public void test_prebuild_null_revision() {
        when(build.getActions(BuildData.class)).thenReturn(Collections.singletonList(mock(BuildData.class)));
        assertTrue(sn.prebuild(build, buildListener));
    }

    @Test
    public void test_build_http_client_with_proxy() throws Exception {
        //given
        StashNotifier sn = spy(this.sn);
        doReturn(new ArrayList<Credentials>()).when(sn).lookupCredentials(
                Mockito.<Class>anyObject(),
                Mockito.<Item>anyObject(),
                Mockito.<Authentication>anyObject(),
                Mockito.<ArrayList<DomainRequirement>>anyObject());

        String address = "192.168.1.1";
        int port = 8080;
        String login = "admin";
        String password = "123";

        Secret secret = mock(Secret.class);
        when(Secret.fromString(password)).thenReturn(secret);
        when(Secret.toString(secret)).thenReturn(password);
        when(secret.getPlainText()).thenReturn(password);

        when(httpClientBuilder.setProxy(any(HttpHost.class))).thenReturn(httpClientBuilder);
        when(httpClientBuilder.setDefaultCredentialsProvider(any(CredentialsProvider.class))).thenReturn(httpClientBuilder);
        when(httpClientBuilder.setProxyAuthenticationStrategy(any(AuthenticationStrategy.class))).thenReturn(httpClientBuilder);

        jenkins.proxy = new ProxyConfiguration(address, port, login, password);
        PrintStream logger = mock(PrintStream.class);

        //when
        sn.getHttpClient(logger, build, "http://localhost");

        //then
        ArgumentCaptor<HttpHost> proxyCaptor = ArgumentCaptor.forClass(HttpHost.class);
        verify(httpClientBuilder).setProxy(proxyCaptor.capture());
        HttpHost proxy = proxyCaptor.getValue();
        //address
        assertThat(proxy.getHostName(), is(address));
        assertThat(proxy.getPort(), is(port));
        assertThat(proxy.getSchemeName(), is("http"));

        ArgumentCaptor<CredentialsProvider> credentialsProviderCaptor = ArgumentCaptor.forClass(CredentialsProvider.class);
        verify(httpClientBuilder).setDefaultCredentialsProvider(credentialsProviderCaptor.capture());
        CredentialsProvider credentialsProvider = credentialsProviderCaptor.getValue();
        org.apache.http.auth.UsernamePasswordCredentials credentials = (UsernamePasswordCredentials) credentialsProvider.getCredentials(new AuthScope(proxy));
        //credentials
        assertThat(credentials.getUserName(), is(login));
        assertThat(credentials.getPassword(), is(password));
    }

    @Test
    public void test_build_http_client_https() throws Exception {
        //given
        sn = spy(new StashNotifier(
                "https://localhost",
                "scot",
                true,
                null,
                true,
                null,
                false,
                false,
                false,
                new JenkinsLocationConfiguration()));

        doReturn(new ArrayList<Credentials>()).when(sn).lookupCredentials(
                Mockito.<Class>anyObject(),
                Mockito.<Item>anyObject(),
                Mockito.<Authentication>anyObject(),
                Mockito.<ArrayList<DomainRequirement>>anyObject());
        PrintStream logger = mock(PrintStream.class);

        //when
        sn.getHttpClient(logger, build, "https://localhost");

        //then
        verify(httpClientBuilder).setSSLSocketFactory(any(SSLConnectionSocketFactory.class));
        verify(httpClientBuilder).setConnectionManager(any(HttpClientConnectionManager.class));
    }

    private void test_perform_buildstep(Result result,
                                        PrintStream logger,
                                        NotificationResult notificationResult,
                                        List<String> hashes) throws Exception {
        //given
        Launcher launcher = test_perform(result, logger, notificationResult, hashes);

        //when
        boolean perform = sn.perform(build, launcher, buildListener);

        //then
        assertThat(perform, is(true));
    }

    private void test_perform_simplebuildstep(Result result,
                                              PrintStream logger,
                                              NotificationResult notificationResult,
                                              List<String> hashes) throws Exception {
        //given
        Launcher launcher = test_perform(result, logger, notificationResult, hashes);

        //when
        sn.perform(build, workspace, launcher, buildListener);

        //then
        assertThat(build.getResult(), is(result));
    }

    private Launcher test_perform(Result result, PrintStream logger, NotificationResult notificationResult, List<String> hashes) throws Exception {
        when(buildListener.getLogger()).thenReturn(logger);
        when(build.getResult()).thenReturn(result);
        Launcher launcher = mock(Launcher.class);
        sn = spy(sn);
        doReturn(hashes).when(sn).lookupCommitSha1s(eq(build), eq((FilePath) null), eq(buildListener));
        doReturn(notificationResult).when(sn).notifyStash(
                any(PrintStream.class),
                any(AbstractBuild.class),
                eq(sha1),
                eq(buildListener),
                any(StashBuildState.class)
        );
        return launcher;
    }

    @Test
    public void test_perform_build_step_success() throws Exception {
        //given
        ArrayList<String> hashes = new ArrayList<>();
        hashes.add(sha1);
        PrintStream logger = mock(PrintStream.class);

        //when
        test_perform_buildstep(Result.SUCCESS, logger, new NotificationResult(true, ""), hashes);

        //then
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger).println(messageCaptor.capture());
        assertThat(messageCaptor.getValue(), is(containsString("Notified Bitbucket for commit with id")));
    }

    @Test
    public void test_perform_build_step_success_for_unstable_build() throws Exception {
        //given
        sn = buildStashNotifier("http://localhost", false, true);
        ArrayList<String> hashes = new ArrayList<>();
        hashes.add(sha1);
        PrintStream logger = mock(PrintStream.class);

        //when
        test_perform_buildstep(Result.UNSTABLE, logger, new NotificationResult(true, ""), hashes);

        //then
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger, atLeastOnce()).println(messageCaptor.capture());
        List<String> values = messageCaptor.getAllValues();
        assertThat(values.get(0), is(containsString("UNSTABLE reported to Bitbucket as SUCCESSFUL")));
        assertThat(values.get(1), is(containsString("Notified Bitbucket for commit with id")));
    }

    @Test
    public void test_perform_build_step_aborted_without_notifying_stash() throws Exception {
        //given
        sn = buildStashNotifier("http://localhost", true, true);
        ArrayList<String> hashes = new ArrayList<>();
        hashes.add(sha1);
        PrintStream logger = mock(PrintStream.class);

        //when
        test_perform_buildstep(Result.ABORTED, logger, new NotificationResult(true, ""), hashes);

        //then
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger).println(messageCaptor.capture());
        assertThat(messageCaptor.getValue(), containsString("ABORTED"));
    }

    @Test
    public void test_perform_build_step_failure() throws Exception {
        //given
        ArrayList<String> hashes = new ArrayList<>();
        hashes.add(sha1);
        PrintStream logger = mock(PrintStream.class);

        //when
        test_perform_buildstep(Result.FAILURE, logger, new NotificationResult(false, ""), hashes);

        //then
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger).println(messageCaptor.capture());
        assertThat(messageCaptor.getValue(), is(containsString("Failed to notify Bitbucket for commit")));
    }

    @Test
    public void test_perform_build_step_not_built() throws Exception {
        //given
        ArrayList<String> hashes = new ArrayList<>();
        hashes.add(sha1);
        PrintStream logger = mock(PrintStream.class);

        //when
        test_perform_buildstep(Result.NOT_BUILT, logger, new NotificationResult(false, ""), hashes);

        //then
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger).println(messageCaptor.capture());
        assertThat(messageCaptor.getValue(), is(containsString("NOT BUILT")));
    }

    @Test
    public void test_perform_build_step_empty_hash() throws Exception {
        //given
        PrintStream logger = mock(PrintStream.class);
        when(buildListener.getLogger()).thenReturn(logger);
        when(build.getResult()).thenReturn(Result.SUCCESS);
        sn = spy(sn);
        doReturn(new ArrayList<String>()).when(sn).lookupCommitSha1s(eq(build), eq((FilePath) null), eq(buildListener));

        //when
        boolean perform = sn.perform(build, mock(Launcher.class), buildListener);

        //then
        assertThat(perform, is(true));
        verify(sn, never()).notifyStash(
                any(PrintStream.class),
                any(AbstractBuild.class),
                anyString(),
                eq(buildListener),
                any(StashBuildState.class)
        );
        verify(logger).println("found no commit info");
    }

    @Test
    public void test_perform_simple_build_step_success() throws Exception {
        //given
        ArrayList<String> hashes = new ArrayList<>();
        hashes.add(sha1);
        PrintStream logger = mock(PrintStream.class);

        //when
        test_perform_simplebuildstep(Result.SUCCESS, logger, new NotificationResult(true, ""), hashes);

        //then
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger).println(messageCaptor.capture());
        assertThat(messageCaptor.getValue(), is(containsString("Notified Bitbucket for commit with id")));
    }


    @Test
    public void test_perform_simple_build_step_failure() throws Exception {
        //given
        ArrayList<String> hashes = new ArrayList<>();
        hashes.add(sha1);
        PrintStream logger = mock(PrintStream.class);

        //when
        test_perform_simplebuildstep(Result.FAILURE, logger, new NotificationResult(false, ""), hashes);

        //then
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger).println(messageCaptor.capture());
        assertThat(messageCaptor.getValue(), is(containsString("Failed to notify Bitbucket for commit")));
    }

    @Test
    public void test_perform_simple_build_step_not_built() throws Exception {
        //given
        ArrayList<String> hashes = new ArrayList<>();
        hashes.add(sha1);
        PrintStream logger = mock(PrintStream.class);

        //when
        test_perform_simplebuildstep(Result.NOT_BUILT, logger, new NotificationResult(false, ""), hashes);

        //then
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(logger).println(messageCaptor.capture());
        assertThat(messageCaptor.getValue(), is(containsString("NOT BUILT")));
    }

    @Test
    public void test_perform_simple_build_step_empty_hash() throws Exception {
        //given
        PrintStream logger = mock(PrintStream.class);
        when(buildListener.getLogger()).thenReturn(logger);
        when(build.getResult()).thenReturn(Result.SUCCESS);
        sn = spy(sn);
        doReturn(new ArrayList<String>()).when(sn).lookupCommitSha1s(eq(build), eq((FilePath) null), eq(buildListener));

        //when
        sn.perform(build, workspace, mock(Launcher.class), buildListener);

        //then
        verify(sn, never()).notifyStash(
                any(PrintStream.class),
                any(AbstractBuild.class),
                anyString(),
                eq(buildListener),
                any(StashBuildState.class)
        );
        verify(logger).println("found no commit info");
    }

    @Test
    public void lookupCommitSha1s() throws InterruptedException, MacroEvaluationException, IOException {
        PowerMockito.mockStatic(TokenMacro.class);
        PowerMockito.when(TokenMacro.expandAll(build, buildListener, sha1)).thenReturn(sha1);
        sn = new StashNotifier(
                "https://localhost",
                "scot",
                true,
                sha1,
                true,
                null,
                false,
                false,
                false,
                new JenkinsLocationConfiguration());

        Collection<String> hashes = sn.lookupCommitSha1s(build, null, buildListener);

        assertThat(hashes.size(), is(1));
        assertThat(hashes.iterator().next(), is(sha1));
    }


    public void lookupCommitSha1s_Exception(Exception e) throws InterruptedException, MacroEvaluationException, IOException {
        //given
        PrintStream logger = mock(PrintStream.class);
        when(buildListener.getLogger()).thenReturn(logger);
        PowerMockito.mockStatic(TokenMacro.class);
        PowerMockito.when(TokenMacro.expandAll(build, buildListener, sha1)).thenThrow(e);
        sn = new StashNotifier(
                "http://localhost",
                "scot",
                true,
                sha1,
                true,
                null,
                false,
                false,
                false,
                new JenkinsLocationConfiguration());

        //when
        Collection<String> hashes = sn.lookupCommitSha1s(build, null, buildListener);

        //then
        assertThat(hashes.isEmpty(), is(true));
        verify(logger).println("Unable to expand commit SHA value");
    }

    @Test
    public void test_lookupCommitSha1s_IOException() throws InterruptedException, MacroEvaluationException, IOException {
        lookupCommitSha1s_Exception(new IOException("BOOM"));
    }

    @Test
    public void test_lookupCommitSha1s_InterruptedException() throws InterruptedException, MacroEvaluationException, IOException {
        lookupCommitSha1s_Exception(new InterruptedException("BOOM"));
    }

    @Test
    public void test_lookupCommitSha1s_MacroEvaluationException() throws InterruptedException, MacroEvaluationException, IOException {
        lookupCommitSha1s_Exception(new MacroEvaluationException("BOOM"));
    }

    @Test
    public void test_getBuildDescription() throws InterruptedException, MacroEvaluationException, IOException {
        //given
        AbstractBuild build = mock(AbstractBuild.class);
        when(build.getDescription()).thenReturn("some description");

        //when
        String description = sn.getBuildDescription(build, StashBuildState.FAILED);

        //then
        assertThat(description, is("some description"));
    }

    private String getBuildDescriptionWhenBuildDescriptionIsNull(StashBuildState buildState) throws InterruptedException, MacroEvaluationException, IOException {
        return sn.getBuildDescription(mock(AbstractBuild.class), buildState);
    }

    @Test
    public void test_getBuildDescription_state() throws InterruptedException, MacroEvaluationException, IOException {
        assertThat(getBuildDescriptionWhenBuildDescriptionIsNull(StashBuildState.SUCCESSFUL), is("built by Jenkins @ http://localhost/"));
        assertThat(getBuildDescriptionWhenBuildDescriptionIsNull(StashBuildState.FAILED), is("built by Jenkins @ http://localhost/"));
        assertThat(getBuildDescriptionWhenBuildDescriptionIsNull(StashBuildState.INPROGRESS), is("building on Jenkins @ http://localhost/"));
    }

    @Test
    public void test_createRequest() throws AuthenticationException {
        //given
        StashNotifier sn = spy(this.sn);
        ArrayList<Credentials> credentialList = new ArrayList<>();
        UsernamePasswordCredentialsImpl credential = new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "", "", "admin", "tiger");
        credentialList.add(credential);
        doReturn(credentialList).when(sn).lookupCredentials(
                Mockito.<Class>anyObject(),
                Mockito.<Item>anyObject(),
                Mockito.<Authentication>anyObject(),
                Mockito.<ArrayList<DomainRequirement>>anyObject());
        PowerMockito.mockStatic(CredentialsMatchers.class);
        when(CredentialsMatchers.firstOrNull(anyCollection(), any(CredentialsMatcher.class))).thenReturn(credential);

        //when
        HttpPost request = sn.createRequest(mock(HttpEntity.class), mock(Item.class), sha1, "http://localhost");

        //then
        assertThat(request, is(not(nullValue())));
        assertThat(request.getHeaders("Authorization"), is(not(nullValue())));
    }

    @Test
    public void test_getBuildKey() throws InterruptedException, MacroEvaluationException, IOException {
        //given
        String key = "someKey";
        PrintStream logger = mock(PrintStream.class);
        when(buildListener.getLogger()).thenReturn(logger);
        PowerMockito.mockStatic(TokenMacro.class);
        PowerMockito.when(TokenMacro.expandAll(build, buildListener, key)).thenReturn(key);

        sn = new StashNotifier(
                "",
                "scot",
                true,
                null,
                true,
                key,
                true,
                false,
                false,
                new JenkinsLocationConfiguration());

        String buildKey = sn.getBuildKey(build, buildListener);
        assertThat(buildKey, is(key));
    }


    @Test
    public void test_getRunKey() throws InterruptedException, MacroEvaluationException, IOException {
        //given
        String key = "someKey";
        PrintStream logger = mock(PrintStream.class);
        when(buildListener.getLogger()).thenReturn(logger);
        final File tempDir = File.createTempFile("stashNotifier", null);
        when(run.getRootDir()).thenReturn(tempDir);
        PowerMockito.mockStatic(TokenMacro.class);
        PowerMockito.when(TokenMacro.expandAll(run, new FilePath(tempDir), buildListener, key)).thenReturn(key);

        sn = new StashNotifier(
                "",
                "scot",
                true,
                null,
                true,
                key,
                true,
                false,
                false,
                new JenkinsLocationConfiguration());

        String buildKey = sn.getBuildKey(run, buildListener);
        assertThat(buildKey, is(key));
    }


    public void getBuildKey_Exception(Exception e) throws InterruptedException, MacroEvaluationException, IOException {
        //given
        String key = "someKey";
        PrintStream logger = mock(PrintStream.class);
        when(buildListener.getLogger()).thenReturn(logger);
        PowerMockito.mockStatic(TokenMacro.class);
        PowerMockito.when(TokenMacro.expandAll(build, buildListener, key)).thenThrow(e);

        sn = new StashNotifier(
                "",
                "scot",
                true,
                null,
                true,
                key,
                true,
                false,
                false,
                new JenkinsLocationConfiguration());

        //when
        String buildKey = sn.getBuildKey(build, buildListener);

        //then
        assertThat(buildKey, is("null-0-http:\\/\\/localhost\\/"));
        verify(logger).println("Cannot expand build key from parameter. Processing with default build key");
    }

    public void getRunKey_Exception(Exception e) throws InterruptedException, MacroEvaluationException, IOException {
        //given
        String key = "someKey";
        PrintStream logger = mock(PrintStream.class);
        when(buildListener.getLogger()).thenReturn(logger);
        final File tempDir = File.createTempFile("stashNotifier", null);
        when(run.getRootDir()).thenReturn(tempDir);
        PowerMockito.mockStatic(TokenMacro.class);
        PowerMockito.when(TokenMacro.expandAll(run, new FilePath(tempDir), buildListener, key)).thenThrow(e);

        sn = new StashNotifier(
                "",
                "scot",
                true,
                null,
                true,
                key,
                true,
                false,
                false,
                new JenkinsLocationConfiguration());

        //when
        String buildKey = sn.getBuildKey(run, buildListener);

        //then
        assertThat(buildKey, is("null-0-http:\\/\\/localhost\\/"));
        verify(logger).println("Cannot expand build key from parameter. Processing with default build key");
    }

    @Test
    public void test_getBuildKey_IOException() throws InterruptedException, MacroEvaluationException, IOException {
        getBuildKey_Exception(new IOException("BOOM"));
    }

    @Test
    public void test_getBuildKey_InterruptedException() throws InterruptedException, MacroEvaluationException, IOException {
        getBuildKey_Exception(new InterruptedException("BOOM"));
    }

    @Test
    public void test_getBuildKey_MacroEvaluationException() throws InterruptedException, MacroEvaluationException, IOException {
        getBuildKey_Exception(new MacroEvaluationException("BOOM"));
    }

    @Test
    public void test_getRunKey_IOException() throws InterruptedException, MacroEvaluationException, IOException {
        getRunKey_Exception(new IOException("BOOM"));
    }

    @Test
    public void test_getRunKey_InterruptedException() throws InterruptedException, MacroEvaluationException, IOException {
        getRunKey_Exception(new InterruptedException("BOOM"));
    }

    @Test
    public void test_getRunKey_MacroEvaluationException() throws InterruptedException, MacroEvaluationException, IOException {
        getRunKey_Exception(new MacroEvaluationException("BOOM"));
    }

    private NotificationResult notifyStash(int statusCode) throws Exception {
        sn = spy(this.sn);
        PrintStream logger = mock(PrintStream.class);
        when(buildListener.getLogger()).thenReturn(logger);
        doReturn("someKey1").when(sn).getBuildKey(eq(build), eq(buildListener));
        HttpPost httpPost = mock(HttpPost.class);
        doReturn(httpPost).when(sn).createRequest(any(HttpEntity.class), any(Item.class), anyString(), anyString());
        CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
        StatusLine sl = mock(StatusLine.class);
        when(sl.getStatusCode()).thenReturn(statusCode);
        when(resp.getStatusLine()).thenReturn(sl);
        when(resp.getEntity()).thenReturn(new StringEntity(""));
        when(client.execute(eq(httpPost))).thenReturn(resp);
        doReturn(client).when(sn).getHttpClient(any(PrintStream.class), any(AbstractBuild.class), anyString());
        return sn.notifyStash(logger, build, sha1, buildListener, StashBuildState.FAILED);
    }

    @Test
    public void notifyStash_success() throws Exception {
        NotificationResult notificationResult = notifyStash(204);
        assertThat(notificationResult.indicatesSuccess, is(true));
    }

    @Test
    public void notifyStash_fail() throws Exception {
        NotificationResult notificationResult = notifyStash(400);
        assertThat(notificationResult.indicatesSuccess, is(false));
    }
}
