package com.checkmarx.flow.cucumber.integration.pullrequest.updatecomments.sast;

import com.checkmarx.flow.CxFlowApplication;
import com.checkmarx.flow.config.ADOProperties;
import com.checkmarx.flow.config.FlowProperties;
import com.checkmarx.flow.config.GitHubProperties;
import com.checkmarx.flow.controller.ADOController;
import com.checkmarx.flow.controller.GitHubController;
import com.checkmarx.flow.cucumber.integration.pullrequest.updatecomments.CommonUpdatePullRequestsComments;
import com.checkmarx.flow.dto.BugTracker;
import com.checkmarx.flow.dto.RepoComment;
import com.checkmarx.flow.dto.ScanRequest;
import com.checkmarx.flow.exception.ExitThrowable;
import com.checkmarx.flow.service.*;
import com.checkmarx.sdk.config.CxProperties;
import com.checkmarx.sdk.config.ScaProperties;
import com.checkmarx.sdk.dto.Filter;
import com.checkmarx.sdk.dto.ScanResults;
import com.checkmarx.sdk.dto.filtering.FilterConfiguration;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.Assert;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.checkmarx.flow.cucumber.common.Constants.CUCUMBER_DATA_DIR;

@SpringBootTest(classes = {CxFlowApplication.class})
@Slf4j
public class SastUpdatePullRequestCommentsSteps extends CommonUpdatePullRequestsComments {

    private static final String INPUT_BASE_PATH = CUCUMBER_DATA_DIR + "/sample-sast-results/5-findings-2-high-3-medium.xml";
    private final static String GIT_PROJECT_NAME = "VB";
    private final static String ADO_PROJECT_NAME = "AdoPullRequestTests-master";

    private ScanRequest gitHubScanRequest;
    private ScanRequest adoScanRequest;

    public SastUpdatePullRequestCommentsSteps(GitHubService gitHubService, GitHubProperties gitHubProperties,
                                              GitHubController gitHubController, ADOService adoService,
                                              ADOController adoController, FlowProperties flowProperties,
                                              CxProperties cxProperties, ScaProperties scaProperties,
                                              SastScanner sastScanner, BugTrackerEventTrigger bugTrackerEventTrigger,
                                              ADOProperties adoProperties) {
        super(gitHubService, gitHubProperties, gitHubController,
                adoService, adoController, flowProperties,
                cxProperties, scaProperties, sastScanner,
                bugTrackerEventTrigger, adoProperties);
    }

    @Before
    public void initMocks() {
        initGitHubProperties(ScannerType.SAST);
        flowProperties.getBranches().add("udi-tests-2");
        flowProperties.setEnabledVulnerabilityScanners(Collections.singletonList("sast"));
        initGitHubControllerSpy();
        initHelperServiceMock();
        gitHubScanRequest = getGitHubBasicScanRequest();
        adoScanRequest = getADOBasicScanRequest();
        setBranches();
    }

    private void setBranches() {
        branchAdo =  "udi-tests-2";
        branchGitHub = "pr-comments-tests";
    }

    @After
    public void cleanUp() throws IOException {
        if (sourceControl.equals(SastUpdatePullRequestCommentsSteps.SourceControlType.GITHUB)) {
            deleteGitHubComments(ScannerType.SAST);
        } else if (sourceControl.equals(SastUpdatePullRequestCommentsSteps.SourceControlType.ADO)) {
            deleteADOComments(ScannerType.SAST);
        }
    }

    @Given("scanner is set to sast")
    public void setScanner() {
        scannerType = CommonUpdatePullRequestsComments.ScannerType.SAST;
        flowProperties.setEnabledVulnerabilityScanners(Collections.singletonList("sast"));
    }

    @Given("different filters configuration is set")
    public void setConfigAsCodeFilters() {
        List<Filter> filterList = new ArrayList<>();
        filterList.add(Filter.builder().type(Filter.Type.SEVERITY).value("High").build());

        switch (sourceControl) {
            case GITHUB:
                gitHubProperties.setConfigAsCode("cx.config.high.json");
                gitHubScanRequest.setFilter(FilterConfiguration.fromSimpleFilters(filterList));
                break;
            case ADO:
                adoProperties.setConfigAsCode("cx.config.high.json");
                adoScanRequest.setFilter(FilterConfiguration.fromSimpleFilters(filterList));
                break;
                default:
        }
    }

    @Given("source control is GitHub")
    public void scGitHub() {
        sourceControl = CommonUpdatePullRequestsComments.SourceControlType.GITHUB;
    }

    @Given("source control is ADO")
    public void scAdo() {
        sourceControl = CommonUpdatePullRequestsComments.SourceControlType.ADO;
    }


    @Given("no comments on pull request")
    public void deletePRComments() throws IOException {
        if (sourceControl.equals(SastUpdatePullRequestCommentsSteps.SourceControlType.GITHUB)) {
            deleteGitHubComments(ScannerType.SAST);
        } else if (sourceControl.equals(SastUpdatePullRequestCommentsSteps.SourceControlType.ADO)) {
            deleteADOComments(ScannerType.SAST);
        }
    }

    @When("pull request arrives to CxFlow")
    public void sendPullRequest() throws IOException, ExitThrowable {
        if (sourceControl.equals(SastUpdatePullRequestCommentsSteps.SourceControlType.GITHUB)) {
            bugTrackerEventTrigger.triggerBugTrackerEvent(gitHubScanRequest);
            sastScanner.cxParseResults(gitHubScanRequest, getFileFromResourcePath());
        } else if (sourceControl.equals(SastUpdatePullRequestCommentsSteps.SourceControlType.ADO)) {
            bugTrackerEventTrigger.triggerBugTrackerEvent(adoScanRequest);
            sastScanner.cxParseResults(adoScanRequest, getFileFromResourcePath());
        }
    }

    @Then("Wait for comments")
    public void waitForNewComments() {
        int minutesToWait = 2;
        Awaitility.await().atMost(Duration.ofMinutes(minutesToWait)).pollInterval(Duration.ofSeconds(COMMENTS_POLL_INTERVAL)).until(this::areThereCommentsAtAll);
    }

    @Then("verify new comments")
    public void verifyNewComments() throws IOException {
        int expectedNumOfComments = 2;
        List<RepoComment> comments = getRepoComments();
        Assert.assertEquals("Wrong number of comments", expectedNumOfComments, comments.size());
        comments.stream().forEach(c -> Assert.assertTrue("Comment is not new (probably updated", isCommentNew(c)));

    }

    @Then("Wait for updated comment")
    public void waitForUpdatedComment() {
        Awaitility.await().pollInterval(Duration.ofSeconds(COMMENTS_POLL_INTERVAL)).atMost(Duration.ofSeconds(125)).until(this::isThereUpdatedComment);
    }

    private boolean areThereCommentsAtAll() throws IOException {
        List<RepoComment> comments = getRepoComments();
        if(comments.size() <= 1) {
            return false;
        }
        return areThereCorrectComments(comments);
    }

    private boolean areThereCorrectComments(List<RepoComment> comments){
        boolean foundSast = false;
        boolean foundScanStarted = false;
        for (RepoComment comment : comments) {
            if (PullRequestCommentsHelper.isSastFindingsComment(comment.getComment())) {
                foundSast = true;
            } else if (PullRequestCommentsHelper.isScanStartedComment(comment.getComment())) {
                foundScanStarted = true;
            }
        }
        return foundSast && foundScanStarted;
    }

    private boolean isThereUpdatedComment() throws IOException {
        List<RepoComment> comments = getRepoComments();
        for (RepoComment comment: comments) {
            if (isCommentUpdated(comment)) {
                return true;
            }
        }
        return false;
    }


    private class ScanResultsAnswerer implements Answer<ScanResults> {
        @Override
        public ScanResults answer(InvocationOnMock invocation) {
            return scanResultsToInject;
        }
    }

    private File getFileFromResourcePath() throws IOException {
        return new ClassPathResource(SastUpdatePullRequestCommentsSteps.INPUT_BASE_PATH).getFile();
    }

    private ScanRequest getGitHubBasicScanRequest() {
        return ScanRequest.builder()
                .product(ScanRequest.Product.CX)
                .project(GIT_PROJECT_NAME)
                .namespace("cxflowtestuser")
                .repoName("vb_test_udi")
                .repoType(ScanRequest.Repository.GITHUB)
                .branch("pr-comments-tests")
                .bugTracker(getGitHubPullBugTracker())
                .refs("refs/heads/pr-comments-tests")
                .mergeNoteUri("https://api.github.com/repos/cxflowtestuser/vb_test_pr_comments/issues/6/comments")
                .application("VB")
                .repoUrl("https://github.com/cxflowtestuser/vb_test_pr_comments")
                .build();
    }

    private ScanRequest getADOBasicScanRequest() {
        return ScanRequest.builder()
                .product(ScanRequest.Product.CX)
                .project(ADO_PROJECT_NAME)
                .repoType(ScanRequest.Repository.ADO)
                .repoUrl("https://dev.azure.com/CxNamespace/AdoPullRequestTests/_git/AdoPullRequestTests")
                .repoName("AdoPullRequestTests")
                .branch("master")
                .mergeTargetBranch("udi-tests-2")
                .mergeNoteUri("https://dev.azure.com/CxNamespace/d50fc6e5-a5ab-4123-9bc9-ccb756c0bf16/_apis/git/repositories/a89a9d2f-ab67-4bda-9c56-a571224c2c66/pullRequests/69/threads")
                .refs("refs/heads/master")
                .bugTracker(getADOPullBugTracker())
                .application("AdoPullRequestTests")
                .build();
    }

    private BugTracker getGitHubPullBugTracker() {
        return BugTracker.builder()
                .type(BugTracker.Type.GITHUBPULL)
                .build();
    }

    private BugTracker getADOPullBugTracker() {
        return BugTracker.builder()
                .type(BugTracker.Type.ADOPULL)
                .build();
    }
}