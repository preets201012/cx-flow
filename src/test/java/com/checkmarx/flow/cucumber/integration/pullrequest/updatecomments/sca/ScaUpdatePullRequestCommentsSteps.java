package com.checkmarx.flow.cucumber.integration.pullrequest.updatecomments.sca;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@SpringBootTest(classes = {CxFlowApplication.class})
@Slf4j
public class ScaUpdatePullRequestCommentsSteps extends CommonUpdatePullRequestsComments {

    public ScaUpdatePullRequestCommentsSteps(GitHubService gitHubService, GitHubProperties gitHubProperties, GitHubController gitHubController, ADOService adoService, ADOController adoController, FlowProperties flowProperties, CxProperties cxProperties, ScaProperties scaProperties) {
        super(gitHubService, gitHubProperties, gitHubController, adoService, adoController, flowProperties, cxProperties, scaProperties);
    }

    @Before
    public void initMocks() {
        initGitHubProperties(ScannerType.SCA);
        initSca();
        flowProperties.getBranches().add("udi-tests-2");
        flowProperties.setEnabledVulnerabilityScanners(Arrays.asList("sast"));
        initGitHubControllerSpy();
        initHelperServiceMock();
        setBranches();
    }

    private void setBranches() {
        branchAdo =  "udi-tests-2";
        branchGitHub = "pr-comments-tests";
    }

    @After
    public void cleanUp() throws IOException, InterruptedException {
        if (sourceControl.equals(ScaUpdatePullRequestCommentsSteps.SourceControlType.GITHUB)) {
            deleteGitHubComments(ScannerType.SCA);
        } else if (sourceControl.equals(ScaUpdatePullRequestCommentsSteps.SourceControlType.ADO)) {
            deleteADOComments(ScannerType.SCA);
        }
    }

    @Given("scanner is set to sca")
    public void setScanner() {
        scannerType = CommonUpdatePullRequestsComments.ScannerType.SCA;
        flowProperties.setEnabledVulnerabilityScanners(Collections.singletonList("sca"));
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
    public void deletePRComments() throws IOException, InterruptedException {
        if (sourceControl.equals(ScaUpdatePullRequestCommentsSteps.SourceControlType.GITHUB)) {
            deleteGitHubComments(ScannerType.SCA);
        } else if (sourceControl.equals(ScaUpdatePullRequestCommentsSteps.SourceControlType.ADO)) {
            deleteADOComments(ScannerType.SCA);
        }
    }

    @When("pull request arrives to CxFlow")
    public void sendPullRequest() {
        if (sourceControl.equals(ScaUpdatePullRequestCommentsSteps.SourceControlType.GITHUB)) {
            buildGitHubPullRequest(ScannerType.SCA);
        } else if (sourceControl.equals(ScaUpdatePullRequestCommentsSteps.SourceControlType.ADO)) {
            buildADOSCAPullRequestEvent();
        }
    }

    @Then("Wait for comments")
    public void waitForNewComments() {
        int minutesToWait = 2;
        Awaitility.await().atMost(Duration.ofMinutes(minutesToWait)).pollInterval(Duration.ofSeconds(COMMENTS_POLL_INTERVAL)).until(this::areThereCommentsAtAll);
    }

    @Then("verify new comments")
    public void verifyNewComments() throws IOException {
        int expectedNumOfComments = 1;
        List<RepoComment> comments = getScaRepoComments();
        Assert.assertEquals("Wrong number of comments", expectedNumOfComments, comments.size());
        comments.stream().forEach(c -> Assert.assertTrue("Comment is not new (probably updated", isCommentNew(c)));

    }

    @Then("Wait for updated comment")
    public void waitForUpdatedComment() {
        Awaitility.await().pollInterval(Duration.ofSeconds(COMMENTS_POLL_INTERVAL)).atMost(Duration.ofSeconds(125)).until(this::isThereUpdatedComment);
    }

    private boolean areThereCommentsAtAll() throws IOException {
        List<RepoComment> comments = getScaRepoComments();
        if (comments.size() < 1) {
            return false;
        }
        return areThereCorrectComments(comments);
    }

    private boolean areThereCorrectComments(List<RepoComment> comments){
        for (RepoComment comment : comments) {
            if (PullRequestCommentsHelper.isScaComment(comment.getComment())) {
                return true;
            }
        }
        return false;
    }

    private boolean isThereUpdatedComment() throws IOException {
        List<RepoComment> comments = getScaRepoComments();
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