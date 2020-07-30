package com.checkmarx.flow.cucumber.integration.pullrequest.updatecomments;

import com.checkmarx.flow.config.FlowProperties;
import com.checkmarx.flow.config.GitHubProperties;
import com.checkmarx.flow.controller.ADOController;
import com.checkmarx.flow.controller.GitHubController;
import com.checkmarx.flow.dto.ControllerRequest;
import com.checkmarx.flow.dto.RepoComment;
import com.checkmarx.flow.dto.azure.AdoDetailsRequest;
import com.checkmarx.flow.dto.azure.Project;
import com.checkmarx.flow.dto.azure.Resource;
import com.checkmarx.flow.dto.github.*;
import com.checkmarx.flow.service.ADOService;
import com.checkmarx.flow.service.GitHubService;
import com.checkmarx.flow.service.HelperService;
import com.checkmarx.sdk.config.CxProperties;
import com.checkmarx.sdk.config.ScaProperties;
import com.checkmarx.sdk.dto.ScanResults;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

public class CommonUpdatePullRequestsComments {

    protected static final int COMMENTS_POLL_INTERVAL = 5;

    private static final String GIT_PROJECT_NAME = "vb_test_pr_comments";
    private static final String GITHUB_PR_BASE_URL = "https://api.github.com/repos/cxflowtestuser/" + GIT_PROJECT_NAME;
    private static final String GITHUB_PR_ID = "6";
    private static final String PULL_REQUEST_COMMENTS_URL = GITHUB_PR_BASE_URL + "/issues/"+ GITHUB_PR_ID + "/comments";
    private static final String GIT_URL = "https://github.com/cxflowtestuser/" + GIT_PROJECT_NAME;

    private static final String GIT_SCA_PROJECT_NAME = "vb_test_pr_sca_comments";
    private static final String GITHUB_PR_SCA_BASE_URL = "https://api.github.com/repos/cxflowtestuser/" + GIT_SCA_PROJECT_NAME;
    private static final String GITHUB_SCA_PR_ID = "1";
    private static final String PULL_REQUEST_COMMENTS_SCA_URL = GITHUB_PR_SCA_BASE_URL + "/issues/"+ GITHUB_SCA_PR_ID + "/comments";
    private static final String GIT_SCA_URL = "https://github.com/cxflowtestuser/" + GIT_SCA_PROJECT_NAME;

    private static final String GIT_BOTH_PROJECT_NAME = "vb_test_pr_sast_and_sca_comments";
    private static final String GITHUB_PR_BOTH_BASE_URL = "https://api.github.com/repos/cxflowtestuser/" + GIT_BOTH_PROJECT_NAME;
    private static final String GITHUB_BOTH_PR_ID = "1";
    private static final String PULL_REQUEST_COMMENTS_BOTH_URL = GITHUB_PR_BOTH_BASE_URL + "/issues/"+ GITHUB_BOTH_PR_ID + "/comments";
    private static final String GIT_BOTH_URL = "https://github.com/cxflowtestuser/" + GIT_BOTH_PROJECT_NAME;

    private static final String ADO_PR_ID = "69";
    private static final String ADO_PR_COMMENTS_URL = "https://dev.azure.com/CxNamespace/d50fc6e5-a5ab-4123-9bc9-ccb756c0bf16/_apis/git/repositories/a89a9d2f-ab67-4bda-9c56-a571224c2c66/pullRequests/" + ADO_PR_ID + "/threads";

    private static final String ADO_SCA_PR_ID = "73";
    private static final String ADO_SCA_PR_COMMENTS_URL = "https://dev.azure.com/CxNamespace/a8df05b5-1061-480b-b443-de29ca21a1a6/_apis/git/repositories/2cabd60a-9468-446c-9093-fcc7d808f71b/pullRequests/" + ADO_SCA_PR_ID + "/threads";

    private static final String ADO_BOTH_PR_ID = "74";
    private static final String ADO_BOTH_PR_COMMENTS_URL = "https://dev.azure.com/CxNamespace/a4828223-9a6a-4612-98d4-3b681aee5bfb/_apis/git/repositories/c69c0d7d-dbe9-4640-aaeb-8b5230e99c8f/pullRequests/" + ADO_BOTH_PR_ID + "/threads";


    protected final ObjectMapper mapper = new ObjectMapper();
    protected final GitHubService gitHubService;
    protected final GitHubProperties gitHubProperties;
    protected final HelperService helperService;
    protected final ScaProperties scaProperties;

    private final ADOService adoService;
    private GitHubController gitHubControllerSpy;
    private ADOController adoControllerSpy;

    protected ScanResults scanResultsToInject;
    protected CommonUpdatePullRequestsComments.SourceControlType sourceControl;
    protected FlowProperties flowProperties;
    protected CxProperties cxProperties;
    protected String branchAdo;
    protected String branchGitHub;
    protected CommonUpdatePullRequestsComments.ScannerType scannerType;

    public CommonUpdatePullRequestsComments(GitHubService gitHubService, GitHubProperties gitHubProperties, GitHubController gitHubController, ADOService adoService, ADOController adoController, FlowProperties flowProperties, CxProperties cxProperties, ScaProperties scaProperties) {
        this.helperService = mock(HelperService.class);
        this.gitHubService = gitHubService;
        this.gitHubProperties = gitHubProperties;
        this.gitHubControllerSpy = Mockito.spy(gitHubController);
        this.adoService = adoService;
        this.adoControllerSpy = Mockito.spy(adoController);
        this.flowProperties = flowProperties;
        this.cxProperties = cxProperties;
        this.scaProperties = scaProperties;
    }

    protected void initSca() {
        scaProperties.setAppUrl("https://sca.scacheckmarx.com");
        scaProperties.setApiUrl("https://api.scacheckmarx.com");
        scaProperties.setAccessControlUrl("https://platform.checkmarx.net");
    }

    protected void deleteADOComments(ScannerType scannerType) throws IOException {
        List<RepoComment> adoComments = getRepoComments(scannerType);

        for (RepoComment rc: adoComments) {
            adoService.deleteComment(rc.getCommentUrl());
        }
    }

    protected List<RepoComment> getRepoComments() throws IOException {
        if (sourceControl.equals(CommonUpdatePullRequestsComments.SourceControlType.GITHUB)) {
            return gitHubService.getComments(PULL_REQUEST_COMMENTS_URL);
        }
        else if (sourceControl.equals(CommonUpdatePullRequestsComments.SourceControlType.ADO)){
            return adoService.getComments(ADO_PR_COMMENTS_URL);
        }
        throw new IllegalArgumentException("Unknown source control: " + sourceControl);
    }

    protected List<RepoComment> getScaRepoComments() throws IOException {
        if (sourceControl.equals(CommonUpdatePullRequestsComments.SourceControlType.GITHUB)) {
            return gitHubService.getComments(PULL_REQUEST_COMMENTS_SCA_URL);
        }
        else if (sourceControl.equals(CommonUpdatePullRequestsComments.SourceControlType.ADO)){
            return adoService.getComments(ADO_SCA_PR_COMMENTS_URL);
        }
        throw new IllegalArgumentException("Unknown source control: " + sourceControl);
    }

    protected List<RepoComment> getSastAndScaRepoComments() throws IOException {
        if (sourceControl.equals(CommonUpdatePullRequestsComments.SourceControlType.GITHUB)) {
            return gitHubService.getComments(PULL_REQUEST_COMMENTS_BOTH_URL);
        }
        else if (sourceControl.equals(CommonUpdatePullRequestsComments.SourceControlType.ADO)){
            return adoService.getComments(ADO_BOTH_PR_COMMENTS_URL);
        }
        throw new IllegalArgumentException("Unknown source control: " + sourceControl);
    }

    protected void deleteGitHubComments(ScannerType scannerType) throws IOException {
        List<RepoComment> comments = getRepoComments(scannerType);
        for (RepoComment comment: comments) {
            gitHubService.deleteComment(comment.getCommentUrl());
        }
    }

    protected boolean isCommentNew(RepoComment comment) {
        return comment.getUpdateTime().equals(comment.getCreatedAt());
    }

    protected boolean isCommentUpdated(RepoComment comment) {
        return comment.getUpdateTime().after(comment.getCreatedAt());
    }

    protected void initGitHubControllerSpy() {
        doNothing().when(gitHubControllerSpy).verifyHmacSignature(any(), any());
    }

    protected void initHelperServiceMock() {
        when(helperService.isBranch2Scan(any(), anyList())).thenReturn(true);
        when(helperService.getShortUid()).thenReturn("123456");
    }

    protected void initGitHubProperties(ScannerType scannerType) {
        this.gitHubProperties.setCxSummary(false);
        this.gitHubProperties.setFlowSummary(false);

        switch (scannerType.name()) {
            case "SAST":
                this.gitHubProperties.setUrl(GIT_URL);
                break;
            case "SCA":
                this.gitHubProperties.setUrl(GIT_SCA_URL);
                break;
            default:
                this.gitHubProperties.setUrl(GIT_BOTH_URL);
                break;
        }
        this.gitHubProperties.setWebhookToken("1234");
        this.gitHubProperties.setApiUrl("https://api.github.com/repos");
    }

    protected void buildGitHubPullRequest(ScannerType scannerType) {
        PullEvent pullEvent = new PullEvent();
        Repository repo = new Repository();

        switch (scannerType.name()) {
            case "SAST":
                repo.setName("vb_test_udi");
                break;
            case "SCA":
                repo.setName("vb_test_pr_sca_comments");
                break;
            default:
                repo.setName("vb_test_pr_sast_and_sca_comments");
                break;
        }

        repo.setCloneUrl(gitHubProperties.getUrl());
        Owner owner = new Owner();
        owner.setName("");
        owner.setLogin("cxflowtestuser");
        repo.setOwner(owner);
        pullEvent.setRepository(repo);
        pullEvent.setAction("opened");
        PullRequest pullRequest = new PullRequest();
        pullRequest.setIssueUrl("");
        Head headBranch = new Head();
        headBranch.setRef(branchGitHub);

        pullRequest.setHead(headBranch);
        pullRequest.setBase(new Base());
        pullRequest.setStatusesUrl("");

        switch (scannerType.name()) {
            case "SAST":
                pullRequest.setIssueUrl(GITHUB_PR_BASE_URL + "/issues/" + GITHUB_PR_ID);
                break;
            case "SCA":
                pullRequest.setIssueUrl(GITHUB_PR_SCA_BASE_URL + "/issues/" + GITHUB_SCA_PR_ID);
                break;
            default:
                pullRequest.setIssueUrl(GITHUB_PR_BOTH_BASE_URL + "/issues/" + GITHUB_BOTH_PR_ID);
                break;
        }

        pullEvent.setPullRequest(pullRequest);

        try {
            String pullEventStr = mapper.writeValueAsString(pullEvent);
            ControllerRequest controllerRequest = new ControllerRequest();
            controllerRequest.setApplication("VB");
            controllerRequest.setBranch(Collections.singletonList(branchGitHub));
            controllerRequest.setProject("VB");
            controllerRequest.setTeam("\\CxServer\\SP");
            controllerRequest.setPreset("default");
            controllerRequest.setIncremental(false);
            gitHubControllerSpy.pullRequest(
                    pullEventStr,
                    "SIGNATURE",
                    "CX",
                    controllerRequest
            );

        } catch (JsonProcessingException e) {
            fail("Unable to parse " + pullEvent.toString());
        }
    }

    protected void buildADOSASTPullRequestEvent() {
        com.checkmarx.flow.dto.azure.PullEvent pullEvent = new com.checkmarx.flow.dto.azure.PullEvent();
        pullEvent.setEventType("git.pullrequest.updated");
        pullEvent.setId("4519989c-c157-4bf8-9651-e94b8d0fca27");
        pullEvent.setSubscriptionId("25aa3b80-54ed-4b26-976a-b74f94940852");
        pullEvent.setPublisherId("tfs");
        Resource resource = new Resource();
        resource.setStatus("active");
        resource.setSourceRefName("refs/heads/master");
        resource.setTargetRefName("refs/heads/udi-tests-2");
        resource.setUrl("https://dev.azure.com/CxNamespace/d50fc6e5-a5ab-4123-9bc9-ccb756c0bf16/_apis/git/repositories/a89a9d2f-ab67-4bda-9c56-a571224c2c66/pullRequests/" + ADO_PR_ID);
        com.checkmarx.flow.dto.azure.Repository repo = new com.checkmarx.flow.dto.azure.Repository();
        repo.setId("a89a9d2f-ab67-4bda-9c56-a571224c2c66");
        repo.setName("AdoPullRequestTests");
        repo.setUrl("https://dev.azure.com/CxNamespace/d50fc6e5-a5ab-4123-9bc9-ccb756c0bf16/_apis/git/repositories/a89a9d2f-ab67-4bda-9c56-a571224c2c66");
        repo.setRemoteUrl("https://CxNamespace@dev.azure.com/CxNamespace/AdoPullRequestTests/_git/AdoPullRequestTests");
        repo.setSshUrl("git@ssh.dev.azure.com:v3/CxNamespace/AdoPullRequestTests/AdoPullRequestTests");
        repo.setWebUrl("https://dev.azure.com/CxNamespace/AdoPullRequestTests/_git/AdoPullRequestTests");
        Project pr = new Project();
        pr.setId("d50fc6e5-a5ab-4123-9bc9-ccb756c0bf16");
        pr.setName("AdoPullRequestTests");
        repo.setProject(pr);
        resource.setRepository(repo);

        pullEvent.setResource(resource);
        ControllerRequest controllerRequest = new ControllerRequest();
        controllerRequest.setProject("AdoPullRequestTests-master");
        controllerRequest.setTeam("\\CxServer\\SP");
        AdoDetailsRequest adoRequest = new AdoDetailsRequest();
        adoControllerSpy.pullRequest(pullEvent,"Basic Y3hmbG93OjEyMzQ=", null, controllerRequest, adoRequest);
    }

    protected void buildADOSCAPullRequestEvent() {
        com.checkmarx.flow.dto.azure.PullEvent pullEvent = new com.checkmarx.flow.dto.azure.PullEvent();
        pullEvent.setEventType("git.pullrequest.updated");
        pullEvent.setId("4de51357-7c01-4975-9c59-9f915e397a68");
        pullEvent.setSubscriptionId("b047d676-694e-4654-ac68-6c5ca74fa8d2");
        pullEvent.setPublisherId("tfs");
        Resource resource = new Resource();
        resource.setStatus("active");
        resource.setSourceRefName("refs/heads/udi-tests-2");
        resource.setTargetRefName("refs/heads/master");
        resource.setUrl("https://dev.azure.com/CxNamespace/a8df05b5-1061-480b-b443-de29ca21a1a6/_apis/git/repositories/2cabd60a-9468-446c-9093-fcc7d808f71b/pullRequests/" + ADO_SCA_PR_ID);
        com.checkmarx.flow.dto.azure.Repository repo = new com.checkmarx.flow.dto.azure.Repository();
        repo.setId("2cabd60a-9468-446c-9093-fcc7d808f71b");
        repo.setName("AdoPullRequestTestsSca");
        repo.setUrl("https://dev.azure.com/CxNamespace/a8df05b5-1061-480b-b443-de29ca21a1a6/_apis/git/repositories/2cabd60a-9468-446c-9093-fcc7d808f71b");
        repo.setRemoteUrl("https://CxNamespace@dev.azure.com/CxNamespace/AdoPullRequestTestsSca/_git/AdoPullRequestTestsSca");
        repo.setSshUrl("git@ssh.dev.azure.com:v3/CxNamespace/AdoPullRequestTestsSca/AdoPullRequestTestsSca");
        repo.setWebUrl("https://dev.azure.com/CxNamespace/AdoPullRequestTestsSca/_git/AdoPullRequestTestsSca");
        Project pr = new Project();
        pr.setId("a8df05b5-1061-480b-b443-de29ca21a1a6");
        pr.setName("AdoPullRequestTestsSca");
        repo.setProject(pr);
        resource.setRepository(repo);

        pullEvent.setResource(resource);
        ControllerRequest controllerRequest = new ControllerRequest();
        controllerRequest.setProject("AdoPullRequestTestsSca-master");
        controllerRequest.setTeam("\\CxServer\\SP");
        AdoDetailsRequest adoRequest = new AdoDetailsRequest();
        adoControllerSpy.pullRequest(pullEvent,"Basic Y3hmbG93OjEyMzQ=", null, controllerRequest, adoRequest);
    }

    protected void buildADOSastAndSCAPullRequestEvent() {
        com.checkmarx.flow.dto.azure.PullEvent pullEvent = new com.checkmarx.flow.dto.azure.PullEvent();
        pullEvent.setEventType("git.pullrequest.updated");
        pullEvent.setId("88b2a839-0044-4fd6-bb89-0d6c222d6491");
        pullEvent.setSubscriptionId("eeceef61-7177-49a7-8190-8f4a803cf7d1");
        pullEvent.setPublisherId("tfs");
        Resource resource = new Resource();
        resource.setStatus("active");
        resource.setSourceRefName("refs/heads/udi-tests-2");
        resource.setTargetRefName("refs/heads/master");
        resource.setUrl("https://dev.azure.com/CxNamespace/a4828223-9a6a-4612-98d4-3b681aee5bfb/_apis/git/repositories/c69c0d7d-dbe9-4640-aaeb-8b5230e99c8f/pullRequests/" + ADO_BOTH_PR_ID);
        com.checkmarx.flow.dto.azure.Repository repo = new com.checkmarx.flow.dto.azure.Repository();
        repo.setId("c69c0d7d-dbe9-4640-aaeb-8b5230e99c8f");
        repo.setName("AdoPullRequestTestsSastAndSca");
        repo.setUrl("https://dev.azure.com/CxNamespace/a4828223-9a6a-4612-98d4-3b681aee5bfb/_apis/git/repositories/c69c0d7d-dbe9-4640-aaeb-8b5230e99c8f");
        repo.setRemoteUrl("https://CxNamespace@dev.azure.com/CxNamespace/AdoPullRequestTestsSastAndSca/_git/AdoPullRequestTestsSastAndSca");
        repo.setSshUrl("git@ssh.dev.azure.com:v3/CxNamespace/AdoPullRequestTestsSastAndSca/AdoPullRequestTestsSastAndSca");
        repo.setWebUrl("https://dev.azure.com/CxNamespace/AdoPullRequestTestsSastAndSca/_git/AdoPullRequestTestsSastAndSca");
        Project pr = new Project();
        pr.setId("a4828223-9a6a-4612-98d4-3b681aee5bfb");
        pr.setName("AdoPullRequestTestsSastAndSca");
        repo.setProject(pr);
        resource.setRepository(repo);

        pullEvent.setResource(resource);
        ControllerRequest controllerRequest = new ControllerRequest();
        controllerRequest.setProject("AdoPullRequestTestsSastAndSca-master");
        controllerRequest.setTeam("\\CxServer\\SP");
        AdoDetailsRequest adoRequest = new AdoDetailsRequest();
        adoControllerSpy.pullRequest(pullEvent,"Basic Y3hmbG93OjEyMzQ=", null, controllerRequest, adoRequest);
    }

    protected enum ScannerType {
        SAST,
        SCA,
        BOTH;

    }

    protected enum SourceControlType {
        GITHUB,
        ADO;
    }

    private List<RepoComment> getRepoComments(ScannerType scannerType) throws IOException {
        List<RepoComment> adoComments;
        switch (scannerType.name()) {
            case "SAST":
                adoComments = getRepoComments();
                break;
            case "SCA":
                adoComments = getScaRepoComments();
                break;
            default:
                adoComments = getSastAndScaRepoComments();
                break;
        }
        return adoComments;
    }
}