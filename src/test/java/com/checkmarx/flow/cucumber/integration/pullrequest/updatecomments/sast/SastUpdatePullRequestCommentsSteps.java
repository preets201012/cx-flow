package com.checkmarx.flow.cucumber.integration.pullrequest.updatecomments.sast;

import com.checkmarx.flow.CxFlowApplication;
import com.checkmarx.flow.config.FlowProperties;
import com.checkmarx.flow.config.GitHubProperties;
import com.checkmarx.flow.controller.ADOController;
import com.checkmarx.flow.controller.GitHubController;
import com.checkmarx.flow.cucumber.integration.pullrequest.updatecomments.CommonUpdatePullRequestsComments;
import com.checkmarx.flow.dto.RepoComment;
import com.checkmarx.flow.service.ADOService;
import com.checkmarx.flow.service.GitHubService;
import com.checkmarx.flow.service.PullRequestCommentsHelper;
import com.checkmarx.sdk.config.CxProperties;
import com.checkmarx.sdk.config.ScaProperties;
import com.checkmarx.sdk.dto.ScanResults;
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

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

@SpringBootTest(classes = {CxFlowApplication.class})
@Slf4j
public class SastUpdatePullRequestCommentsSteps extends CommonUpdatePullRequestsComments {


    public SastUpdatePullRequestCommentsSteps(GitHubService gitHubService, GitHubProperties gitHubProperties, GitHubController gitHubController, ADOService adoService, ADOController adoController, FlowProperties flowProperties, CxProperties cxProperties, ScaProperties scaProperties) {
        super(gitHubService, gitHubProperties, gitHubController, adoService, adoController, flowProperties, cxProperties, scaProperties);
    }

    @Before
    public void initMocks() {
        initGitHubProperties(ScannerType.SAST);
        flowProperties.getBranches().add("udi-tests-2");
        flowProperties.setEnabledVulnerabilityScanners(Collections.singletonList("sast"));
        initGitHubControllerSpy();
        initHelperServiceMock();
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
        gitHubProperties.setConfigAsCode("cx.config.high.json");
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
    public void sendPullRequest() {
        if (sourceControl.equals(SastUpdatePullRequestCommentsSteps.SourceControlType.GITHUB)) {
            buildGitHubPullRequest(ScannerType.SAST);
        } else if (sourceControl.equals(SastUpdatePullRequestCommentsSteps.SourceControlType.ADO)) {
            buildADOSASTPullRequestEvent();
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
}