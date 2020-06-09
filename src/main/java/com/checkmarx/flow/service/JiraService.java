package com.checkmarx.flow.service;

import com.atlassian.jira.rest.client.api.*;
import com.atlassian.jira.rest.client.api.domain.*;
import com.atlassian.jira.rest.client.api.domain.input.ComplexIssueInputFieldValue;
import com.atlassian.jira.rest.client.api.domain.input.FieldInput;
import com.atlassian.jira.rest.client.api.domain.input.IssueInputBuilder;
import com.atlassian.jira.rest.client.api.domain.input.TransitionInput;
import com.atlassian.jira.rest.client.internal.async.CustomAsynchronousJiraRestClientFactory;
import com.checkmarx.flow.config.FlowProperties;
import com.checkmarx.flow.config.JiraProperties;
import com.checkmarx.flow.constants.JiraConstants;
import com.checkmarx.flow.dto.BugTracker;
import com.checkmarx.flow.dto.ScanDetails;
import com.checkmarx.flow.dto.ScanRequest;
import com.checkmarx.flow.dto.report.JiraTicketsReport;
import com.checkmarx.flow.exception.JiraClientException;
import com.checkmarx.flow.exception.JiraClientRunTimeException;
import com.checkmarx.flow.exception.MachinaRuntimeException;
import com.checkmarx.flow.utils.ScanUtils;
import com.checkmarx.sdk.dto.ScanResults;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import javax.annotation.PostConstruct;
import java.beans.ConstructorProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class JiraService {

    private static final int MAX_RESULTS_ALLOWED = 1000000;
    private static final String JIRA_ISSUE_KEY = "%s%s @ %s [%s]%s";
    private static final String JIRA_ISSUE_KEY_2 = "%s%s @ %s%s";
    private static final String JIRA_ISSUE_KEY_3 = "%s%s (%s:%.1f) @ %s:%s [%s]%s";
    private static final String JIRA_ISSUE_BODY = "*%s* issue exists @ *%s* in branch *%s*";
    private static final String JIRA_ISSUE_BODY_2 = "*%s* issue exists @ *%s*";
    private static final String JIRA_ISSUE_BODY_3 = "*%s Vulnerable Package* issue exists @ *%s* in branch *%s*";

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(JiraService.class);
    private JiraRestClient client;
    private IssueRestClient issueClient;
    private ProjectRestClient projectClient;
    private MetadataRestClient metaClient;
    private URI jiraURI;
    private final JiraProperties jiraProperties;
    private final FlowProperties flowProperties;
    private final String parentUrl;
    private final String grandParentUrl;
    private Map<String, ScanResults.XIssue> nonPublishedScanResultsMap = new HashMap<>();

    private List<String> currentNewIssuesList = new ArrayList<>();
    private List<String> currentUpdatedIssuesList = new ArrayList<>();
    private List<String> currentClosedIssuesList = new ArrayList<>();

    //Map used to store/retrieve custom field values
    private Map<String, Map<String, String>> customFields = new HashMap<>();

    private static final String LABEL_FIELD_TYPE = "labels";
    private static final String SECURITY_FIELD_TYPE = "security";
    private static final String VALUE_FIELD_TYPE = "value";
    private static final String CHILD_FIELD_TYPE = "child";
    private static final String CASCADE_PARENT_CHILD_DELIMITER  = ";";

    @ConstructorProperties({"jiraProperties", "flowProperties"})
    public JiraService(JiraProperties jiraProperties, FlowProperties flowProperties) {
        this.jiraProperties = jiraProperties;
        this.flowProperties = flowProperties;
        parentUrl = jiraProperties.getParentUrl();
        grandParentUrl = jiraProperties.getGrandParentUrl();
    }

    @PostConstruct
    public void init() {
        if (jiraProperties != null && !ScanUtils.empty(jiraProperties.getUrl())) {
            CustomAsynchronousJiraRestClientFactory factory = new CustomAsynchronousJiraRestClientFactory();
            try {
                this.jiraURI = new URI(jiraProperties.getUrl());
                this.client = factory.createWithBasicHttpAuthenticationCustom(jiraURI, jiraProperties.getUsername(), jiraProperties.getToken(), jiraProperties.getHttpTimeout());
                this.issueClient = this.client.getIssueClient();
                this.projectClient = this.client.getProjectClient();
                this.metaClient = this.client.getMetadataClient();
                configJira();
            } catch (URISyntaxException | RestClientException e) {
                log.error("Error constructing URI for JIRA", e);
            }
        }
    }

    private void prepareJiraOpenClosedStatuses() {
        if (jiraProperties.getClosedStatus() == null) {
            jiraProperties.setClosedStatus(new ArrayList<>());
        }
        if (jiraProperties.getOpenStatus() == null) {
            jiraProperties.setOpenStatus(new ArrayList<>());
        }
    }


    private void configJira() {
        if (flowProperties.getBugTracker().equalsIgnoreCase("JIRA") ||
            flowProperties.getBugTrackerImpl().stream().map(String::toLowerCase)
                    .collect(Collectors.toList()).contains("jira"))
        {
            configurOpenClosedStatuses();
        }
    }

    private void configurOpenClosedStatuses() {
        prepareJiraOpenClosedStatuses();
        if (jiraProperties.getClosedStatus().isEmpty()) {
            Iterable<Status> statuses = client.getMetadataClient().getStatuses().claim();
            for(Status status: statuses) {
                if(isStatusClosed(status)) {
                    jiraProperties.getClosedStatus().add(status.getName());
                }
            }
        }

        if (jiraProperties.getOpenStatus().isEmpty()) {
            Iterable<Status> statuses = client.getMetadataClient().getStatuses().claim();
            for(Status status: statuses) {
                if(isStatusOpen(status)) {
                    jiraProperties.getOpenStatus().add(status.getName());
                }
            }
        }
        if (jiraProperties.getClosedStatus().isEmpty() || jiraProperties.getOpenStatus().isEmpty()) {
            throw new JiraClientRunTimeException("Could not find JIRA issues closed statuses.");
        }
    }

    private boolean isStatusClosed(Status status) {
        return jiraProperties.getStatusCategoryClosedName().contains(status.getStatusCategory().getName());
    }

    private boolean isStatusOpen(Status status) {
        return jiraProperties.getStatusCategoryOpenName().contains(status.getStatusCategory().getName());
    }



    private List<Issue> getIssues(ScanRequest request) {
        log.info("Executing getIssues API call");
        List<Issue> issues = new ArrayList<>();
        String jql;
        BugTracker bugTracker = request.getBugTracker();
        /*Namespace/Repo/Branch provided*/
        if (!flowProperties.isTrackApplicationOnly()
                && !flowProperties.isApplicationRepoOnly()
                && !ScanUtils.empty(request.getNamespace())
                && !ScanUtils.empty(request.getRepoName())
                && !ScanUtils.empty(request.getBranch())) {
            jql = String.format("project = %s and issueType = \"%s\" and (\"%s\" = \"%s\" and \"%s\" = \"%s:%s\" and \"%s\" = \"%s:%s\" and \"%s\" = \"%s:%s\")",
                    bugTracker.getProjectKey(),
                    bugTracker.getIssueType(),
                    jiraProperties.getLabelTracker(),
                    request.getProduct().getProduct(),
                    jiraProperties.getLabelTracker(),
                    jiraProperties.getOwnerLabelPrefix(), request.getNamespace(),
                    jiraProperties.getLabelTracker(),
                    jiraProperties.getRepoLabelPrefix(), request.getRepoName(),
                    jiraProperties.getLabelTracker(),
                    jiraProperties.getBranchLabelPrefix(), request.getBranch()
            );
        }/*Only application and repo provided */
        else if (!ScanUtils.empty(request.getApplication()) && !ScanUtils.empty(request.getRepoName())) {

            jql = String.format("project = %s and issueType = \"%s\" and (\"%s\" = \"%s\" and \"%s\" = \"%s:%s\" and \"%s\" = \"%s:%s\")",
                    bugTracker.getProjectKey(),
                    bugTracker.getIssueType(),
                    jiraProperties.getLabelTracker(),
                    request.getProduct().getProduct(),
                    jiraProperties.getLabelTracker(),
                    jiraProperties.getAppLabelPrefix(), request.getApplication(),
                    jiraProperties.getLabelTracker(),
                    jiraProperties.getRepoLabelPrefix(), request.getRepoName()
            );

        }/*Only application provided*/
        else if (!ScanUtils.empty(request.getApplication())) {
            jql = String.format("project = %s and issueType = \"%s\" and (\"%s\" = \"%s\" and \"%s\" = \"%s:%s\")",
                    bugTracker.getProjectKey(),
                    bugTracker.getIssueType(),
                    jiraProperties.getLabelTracker(),
                    request.getProduct().getProduct(),
                    jiraProperties.getLabelTracker(),
                    jiraProperties.getAppLabelPrefix(), request.getApplication()
            );
        } else {
            log.error("Namespace/Repo/Branch or App must be provided in order to properly track ");
            throw new MachinaRuntimeException();
        }
        log.debug("jql query : {}", jql);
        HashSet<String> fields = new HashSet<>();
        fields.add("key");
        fields.add("project");
        fields.add("issuetype");
        fields.add("summary");
        fields.add(LABEL_FIELD_TYPE);
        fields.add("created");
        fields.add("updated");
        fields.add("status");
        int startAt = 0;

        SearchResult searchResults;
        //Retrieve JQL results through pagination (jira.max-jql-results per page -> default 50)
        do {
            searchResults = this.client.getSearchClient().searchJql(jql, jiraProperties.getMaxJqlResults(), startAt, fields).claim();
            for (Issue issue : searchResults.getIssues()) {
                issues.add(issue);
            }
            startAt += jiraProperties.getMaxJqlResults();
        }while(startAt < getTotalResults(searchResults));
        return issues;
    }

    private int getTotalResults(SearchResult searchResults) {
        int totalResults =  searchResults.getTotal();
        if (totalResults > MAX_RESULTS_ALLOWED) {
            totalResults = MAX_RESULTS_ALLOWED;
        }
        return totalResults;

    }

    private Issue getIssue(String bugId) {
        return this.issueClient.getIssue(bugId).claim();
    }

    private IssueType getIssueType(String projectKey, String type) throws RestClientException, JiraClientException {
        List<String> issueTypesList = new ArrayList<>();

        Project project = this.projectClient.getProject(projectKey).claim();
        Iterator<IssueType> issueTypes = project.getIssueTypes().iterator();
        while (issueTypes.hasNext()) {
            IssueType it = issueTypes.next();
            issueTypesList.add(it.getName());
            log.info("getIssueType iterator: {}", it.getName());
            if (it.getName().equals(type)) {
                return it;
            }
        }

        String error = String.format("The defined issue type '%s' was not found. Please make sure it's one of the following issues types: [%s]", type, getIssueTypesFromList(issueTypesList));

        throw new JiraClientException(error);
    }

    private String getIssueTypesFromList(List<String> issueTypesList) {
        return String.join(", ", issueTypesList);
    }

    private String createIssue(ScanResults.XIssue issue, ScanRequest request) throws JiraClientException {
        log.debug("Retrieving issuetype object for project {}, type {}", request.getBugTracker().getProjectKey(), request.getBugTracker().getIssueType());
        try {
            BugTracker bugTracker = request.getBugTracker();
            String assignee = bugTracker.getAssignee();
            String projectKey = bugTracker.getProjectKey();
            String application = request.getApplication();
            String namespace = request.getNamespace();
            String repoName = request.getRepoName();
            String branch = request.getBranch();
            String filename = issue.getFilename();
            String vulnerability = issue.getVulnerability();
            String severity = issue.getSeverity();

            IssueType issueType = this.getIssueType(projectKey, bugTracker.getIssueType());
            IssueInputBuilder issueBuilder = new IssueInputBuilder(projectKey, issueType.getId());
            String issuePrefix = jiraProperties.getIssuePrefix();
            String issuePostfix = jiraProperties.getIssuePostfix();

            if (issuePrefix == null) {
                issuePrefix = "";
            }
            if (issuePostfix == null) {
                issuePostfix = "";
            }

            String summary;

            boolean useBranch = isUseBranch(request);

            if (useBranch) {
                List<ScanResults.ScaDetails> scaDetails = issue.getScaDetails();
                if (scaDetails != null) {
                    ScanResults.ScaDetails details = scaDetails.get(0);
                    summary = String.format(JIRA_ISSUE_KEY_3, issuePrefix, details.getFinding().getId(),
                            details.getFinding().getSeverity(), details.getFinding().getScore(),
                            details.getVulnerabilityPackage().getName(),
                            details.getVulnerabilityPackage().getVersion(), branch, issuePostfix);
                } else {
                    summary = String.format(JIRA_ISSUE_KEY, issuePrefix, vulnerability, filename, branch, issuePostfix);
                }
            } else {
                summary = String.format(JIRA_ISSUE_KEY_2, issuePrefix, vulnerability, filename, issuePostfix);
            }
            String fileUrl = ScanUtils.getFileUrl(request, issue.getFilename());
            summary = checkSummaryLength(summary);

            issueBuilder.setSummary(summary);
            issueBuilder.setDescription(this.getBody(issue, request, fileUrl));
            if (assignee != null && !assignee.isEmpty()) {
                try {
                    User userAssignee = getAssignee(assignee);
                    issueBuilder.setAssignee(userAssignee);
                } catch (RestClientException e) {
                    log.error("Error occurred while assigning to user {}", assignee, e);
                }
            }

            if (bugTracker.getPriorities() != null && bugTracker.getPriorities().containsKey(severity)) {
                issueBuilder.setFieldValue("priority", ComplexIssueInputFieldValue.with("name",
                        bugTracker.getPriorities().get(severity)));
            }

            /*Add labels for tracking existing issues*/
            List<String> labels = new ArrayList<>();
            if (useBranch) {
                labels.add(request.getProduct().getProduct());
                labels.add(jiraProperties.getOwnerLabelPrefix().concat(":").concat(namespace));
                labels.add(jiraProperties.getRepoLabelPrefix().concat(":").concat(repoName));
                labels.add(jiraProperties.getBranchLabelPrefix().concat(":").concat(branch));
            } else if (!ScanUtils.anyEmpty(application, repoName)) {
                labels.add(request.getProduct().getProduct());
                labels.add(jiraProperties.getAppLabelPrefix().concat(":").concat(application));
                labels.add(jiraProperties.getRepoLabelPrefix().concat(":").concat(repoName));
            } else if (!ScanUtils.empty(application)) {
                labels.add(request.getProduct().getProduct());
                labels.add(jiraProperties.getAppLabelPrefix().concat(":").concat(application));
            }
            log.debug("Adding tracker labels: {} - {}", jiraProperties.getLabelTracker(), labels);
            if (!jiraProperties.getLabelTracker().equals(LABEL_FIELD_TYPE)) {
                String customField = getCustomFieldByName(projectKey, bugTracker.getIssueType(), jiraProperties.getLabelTracker());
                issueBuilder.setFieldValue(customField, labels);
            } else {
                issueBuilder.setFieldValue(LABEL_FIELD_TYPE, labels);
            }

            log.debug("Creating JIRA issue");

            mapCustomFields(request, issue, issueBuilder, false);

            log.debug("Creating JIRA issue");
            log.debug(issueBuilder.toString());
            BasicIssue basicIssue = this.issueClient.createIssue(issueBuilder.build()).claim();
            log.debug("JIRA issue {} created", basicIssue.getKey());
            return basicIssue.getKey();
        } catch (RestClientException e) {
            log.error("Error occurred while creating JIRA issue.", e);
            throw new JiraClientException();
        }
    }

    private String checkSummaryLength(String summary) {
        /* Summary can only be 255 chars */
        if (summary.length() > 255) {
            summary = summary.substring(0, 254);
        }
        return summary;
    }

    private boolean isUseBranch(ScanRequest request) {
        return !flowProperties.isTrackApplicationOnly()
                && !ScanUtils.anyEmpty(
                request.getNamespace(),
                request.getRepoName(),
                request.getBranch());
    }

    private Issue updateIssue(String bugId, ScanResults.XIssue issue, ScanRequest request) throws JiraClientException {
        BugTracker bugTracker = request.getBugTracker();
        String severity = issue.getSeverity();
        Issue jiraIssue = this.getIssue(bugId);
        if (bugTracker.getClosedStatus().contains(jiraIssue.getStatus().getName())) {
            this.transitionIssue(bugId, bugTracker.getOpenTransition());
        }
        IssueInputBuilder issueBuilder = new IssueInputBuilder();
        String fileUrl = ScanUtils.getFileUrl(request, issue.getFilename());
        issueBuilder.setDescription(this.getBody(issue, request, fileUrl));

        if (bugTracker.getPriorities().containsKey(severity)) {
            issueBuilder.setFieldValue("priority", ComplexIssueInputFieldValue.with("name",
                    bugTracker.getPriorities().get(severity)));
        }

        log.info("Updating JIRA issue #{}", bugId);

        mapCustomFields(request, issue, issueBuilder, true);

        log.debug("Updating JIRA issue");
        log.debug(issueBuilder.toString());
        try {
            this.issueClient.updateIssue(bugId, issueBuilder.build()).claim();
        } catch (RestClientException e) {
            log.error("Error occurred", e);
            throw new JiraClientException();
        }

        return this.getIssue(bugId);
    }

    /**
     * Map custom JIRA fields to specific values (Custom Cx fields, Issue result
     * fields, static fields
     */
    private void mapCustomFields(ScanRequest request, ScanResults.XIssue issue, IssueInputBuilder issueBuilder, boolean update) {
        BugTracker bugTracker = request.getBugTracker();

        log.debug("Handling custom field mappings");
        if (bugTracker.getFields() == null) {
            return;
        }

        String projectKey = bugTracker.getProjectKey();
        String issueTypeStr = bugTracker.getIssueType();

        for (com.checkmarx.flow.dto.Field f : bugTracker.getFields()) {

            String customField = getCustomFieldByName(projectKey, issueTypeStr, f.getJiraFieldName());
            String value;
            if (update && f.isSkipUpdate()) {
                log.debug("Skip update to field {}", f.getName());
                continue;
            }
            if (!ScanUtils.empty(customField)) {
                /*cx | static | other - specific values that can be linked from scan request or the issue details*/
                String fieldType = f.getType();
                if (ScanUtils.empty(fieldType)) {
                    log.warn("Field type not supplied for custom field: {}. Using 'result' by default.", customField);
                    // use default = result
                    fieldType = "result";
                }

                switch (fieldType) {
                    case "cx":
                        log.debug("Checkmarx custom field {}", f.getName());
                        if (request.getCxFields() != null) {
                            log.debug("Checkmarx custom field");
                            value = request.getCxFields().get(f.getName());
                            log.debug("Cx Field value: {}", value);
                            if (ScanUtils.empty(value) && !ScanUtils.empty(f.getJiraDefaultValue())) {
                                value = f.getJiraDefaultValue();
                                log.debug("JIRA default Value is {}", value);
                            }
                        } else {
                            log.debug("No value found for {}", f.getName());
                            value = "";
                        }
                        break;
                    case "static":
                        log.debug("Static value {} - {}", f.getName(), f.getJiraDefaultValue());
                        value = f.getJiraDefaultValue();
                        break;
                    default:  //result
                        String fieldName = f.getName();
                        if (fieldName == null) {
                            log.warn("Field name not supplied for custom field: {}. Skipping.", customField);
                            /* there is no default, move on to the next field */
                            continue;
                        }
                        /*known values we can use*/
                        switch (fieldName) {
                            case "application":
                                log.debug("application: {}", request.getApplication());
                                value = request.getApplication();
                                break;
                            case "project":
                                log.debug("project: {}", request.getProject());
                                value = request.getProject();
                                break;
                            case "namespace":
                                log.debug("namespace: {}", request.getNamespace());
                                value = request.getNamespace();
                                break;
                            case "repo-name":
                                log.debug("repo-name: {}", request.getRepoName());
                                value = request.getRepoName();
                                break;
                            case "repo-url":
                                log.debug("repo-url: {}", request.getRepoUrl());
                                value = request.getRepoUrl();
                                break;
                            case "branch":
                                log.debug("branch: {}", request.getBranch());
                                value = request.getBranch();
                                break;
                            case "severity":
                                log.debug("severity: {}", issue.getSeverity());
                                value = issue.getSeverity();
                                break;
                            case "category":
                                log.debug("category: {}", issue.getVulnerability());
                                value = issue.getVulnerability();
                                break;
                            case "cwe":
                                log.debug("cwe: {}", issue.getCwe());
                                value = issue.getCwe();
                                break;
                            case "cve":
                                log.debug("cve: {}", issue.getCve());
                                value = issue.getCve();
                                break;
                            case "system-date":
                                DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                                LocalDateTime now = LocalDateTime.now().plusDays(f.getOffset());
                                value = dtf.format(now);
                                log.debug("system date: {}", value);
                                break;
                            case "recommendation":
                                StringBuilder recommendation = new StringBuilder();
                                if (issue.getLink() != null && !issue.getLink().isEmpty()) {
                                    recommendation.append("Checkmarx Link: ").append(issue.getLink()).append(ScanUtils.CRLF);
                                }
                                if (!ScanUtils.empty(issue.getCwe())) {
                                    recommendation.append("Mitre Details: ").append(String.format(flowProperties.getMitreUrl(), issue.getCwe())).append(ScanUtils.CRLF);
                                }
                                if (!ScanUtils.empty(flowProperties.getCodebashUrl())) {
                                    recommendation.append("Training: ").append(flowProperties.getCodebashUrl()).append(ScanUtils.CRLF);
                                }
                                if (!ScanUtils.empty(flowProperties.getWikiUrl())) {
                                    recommendation.append("Guidance: ").append(flowProperties.getWikiUrl()).append(ScanUtils.CRLF);
                                }
                                value = recommendation.toString();
                                break;
                            case "loc":
                                value = "";
                                List<Integer> lines = issue.getDetails().entrySet()
                                        .stream()
                                        .filter(x -> x.getKey() != null && x.getValue() != null && !x.getValue().isFalsePositive())
                                        .map(Map.Entry::getKey)
                                        .collect(Collectors.toList());
                                if (!lines.isEmpty()) {
                                    Collections.sort(lines);
                                    value = StringUtils.join(lines, ",");
                                    log.debug("loc: {}", value);
                                }
                                break;
                            case "not-exploitable":
                                value = "";
                                List<Integer> fpLines;
                                if (issue.getDetails() != null) {
                                    fpLines = issue.getDetails().entrySet()
                                            .stream()
                                            .filter(x -> x.getKey() != null && x.getValue() != null && x.getValue().isFalsePositive())
                                            .map(Map.Entry::getKey)
                                            .collect(Collectors.toList());
                                    if (!fpLines.isEmpty()) {
                                        Collections.sort(fpLines);
                                        value = StringUtils.join(fpLines, ",");
                                        log.debug("loc: {}", value);
                                    }
                                }
                                break;
                            case "site":
                                log.debug("site: {}", request.getSite());
                                value = request.getSite();
                                break;
                            case "issue-link":
                                log.debug("issue-link: {}", issue.getLink());
                                value = issue.getLink();
                                break;
                            case "filename":
                                log.debug("filename: {}", issue.getFilename());
                                value = issue.getFilename();
                                break;
                            case "language":
                                log.debug("language: {}", issue.getLanguage());
                                value = issue.getLanguage();
                                break;
                            case "comment":
                                value = "";
                                StringBuilder comments = new StringBuilder();
                                String commentFmt = "[Line %s]: [%s]".concat(ScanUtils.CRLF);
                                if (issue.getDetails() != null) {
                                    issue.getDetails().entrySet()
                                            .stream()
                                            .filter( x -> x.getKey( ) != null && x.getValue() != null && x.getValue().getComment() != null && !x.getValue().getComment().isEmpty())
                                            .forEach( c -> comments.append(String.format(commentFmt, c.getKey(), c.getValue().getComment())));
                                    value = comments.toString();
                                }
                                break;
                            default:
                                log.warn("field value for {} not found", f.getName());
                                value = "";
                        }
                        /*If the value is missing, check if a default value was specified*/
                        if (ScanUtils.empty(value)) {
                            log.debug("Value is empty, defaulting to configured default (if applicable)");
                            if (!ScanUtils.empty(f.getJiraDefaultValue())) {
                                value = f.getJiraDefaultValue();
                                log.debug("Default value is {}", value);
                            }
                        }
                        break;
                }
                /*Determine the expected custom field type within JIRA*/
                if (!ScanUtils.empty(value)) {
                    String jiraFieldType = f.getJiraFieldType();
                    if (ScanUtils.empty(jiraFieldType)) {
                        log.warn("JIRA field type not supplied for custom field: {}. Using 'text' by default.", f.getName());
                        // use default = text
                        jiraFieldType = "text";
                    }

                    List<String> list;
                    switch (jiraFieldType) {
                        case SECURITY_FIELD_TYPE:
                            log.debug("Security field");
                            SecurityLevel securityLevel = getSecurityLevel(projectKey, issueTypeStr, value);
                            if (securityLevel != null) {
                                log.warn("JIRA Security level was not found: {}", value);
                                issueBuilder.setFieldValue(SECURITY_FIELD_TYPE, securityLevel);
                            }
                            break;
                        case "text":
                            log.debug("text field");
                            issueBuilder.setFieldValue(customField, value);
                            break;
                        case "component":
                            log.debug("component field");
                            issueBuilder.setComponentsNames(Collections.singletonList(value));
                            break;
                        case "label":
                            /*csv to array | replace space with _ */
                            log.debug("label field");
                            String[] l = StringUtils.split(value, ",");
                            list = new ArrayList<>();
                            for (String x : l) {
                                list.add(x.replaceAll("[^a-zA-Z0-9-_]+", "_"));
                            }

                            if (!ScanUtils.empty(list)) {
                                issueBuilder.setFieldValue(customField, list);
                            }
                            break;
                        case "single-select":
                            log.debug("single select field");
                            issueBuilder.setFieldValue(customField, ComplexIssueInputFieldValue.with(VALUE_FIELD_TYPE, value));
                            break;
                        case "radio":
                            log.debug("radio field");
                            issueBuilder.setFieldValue(customField, ComplexIssueInputFieldValue.with(VALUE_FIELD_TYPE, value));
                            break;
                        case "multi-select":
                            log.debug("multi select field");
                            String[] selected = StringUtils.split(value, ",");
                            List<ComplexIssueInputFieldValue> fields = new ArrayList<>();
                            for (String s : selected) {
                                ComplexIssueInputFieldValue fieldValue = ComplexIssueInputFieldValue.with(VALUE_FIELD_TYPE, s.trim());
                                fields.add(fieldValue);
                            }
                            issueBuilder.setFieldValue(customField, fields);
                            break;
                        case "cascading-select":
                            log.debug("cascading select list field");
                            log.debug("cascading values {}", value);
                            addCascadingSelect(issueBuilder, f, customField, value);
                            break;
                        default:
                            log.warn("{} not a valid option for jira field type", f.getJiraFieldType());
                    }
                }
            }
        }
    }

    private void addCascadingSelect(IssueInputBuilder issueBuilder, com.checkmarx.flow.dto.Field f, String customField, String value) {
        // expected value format is "parent;child"
        // neither can be empty; enclose in quotes if spaces/special characters
        // must match case
        String[] selectedValues = StringUtils.split(value, CASCADE_PARENT_CHILD_DELIMITER);
        if(selectedValues.length == 2) {
            Map<String, Object> cascadingValues = new HashMap<String, Object>();
            cascadingValues.put(VALUE_FIELD_TYPE, selectedValues[0].trim());
            cascadingValues.put(CHILD_FIELD_TYPE, ComplexIssueInputFieldValue.with(VALUE_FIELD_TYPE, selectedValues[1].trim()));
            issueBuilder.setFieldValue(customField, new ComplexIssueInputFieldValue(cascadingValues));
        }
        else {
            log.warn("Invalid value for jira field type {}", f.getJiraFieldType());
        }
    }

    private SecurityLevel getSecurityLevel(String projectKey, String issueType, String name) {
        GetCreateIssueMetadataOptions options;
        options = new GetCreateIssueMetadataOptionsBuilder().withExpandedIssueTypesFields().withIssueTypeNames(issueType).withProjectKeys(projectKey).build();
        Iterable<CimProject> metadata = this.issueClient.getCreateIssueMetadata(options).claim();

        CimProject cim = metadata.iterator().next();

        Map<String, CimFieldInfo> fields = cim.getIssueTypes().iterator().next().getFields();
        CimFieldInfo security = fields.get(SECURITY_FIELD_TYPE);
        if (security != null) {
            Iterable<Object> allowedValues = security.getAllowedValues();
            if (allowedValues != null) {
                for (Object lvl : allowedValues) {
                    SecurityLevel secLevel = (SecurityLevel) lvl;
                    if (secLevel.getName().equals(name)) {
                        return secLevel;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Transitions an issue based on the issue id and transition name
     *
     * TODO handle re-open transition fields
     *
     */
    private Issue transitionIssue(String bugId, String transitionName) throws JiraClientException {
        Issue issue;
        try {
            issue = this.issueClient.getIssue(bugId).claim();

            URI transitionURI = issue.getTransitionsUri();
            final Iterable<Transition> transitions = this.issueClient.getTransitions(transitionURI).claim();
            final Transition transition = getTransitionByName(transitions, transitionName);
            if (transition != null) {
                this.issueClient.transition(issue.getTransitionsUri(), new TransitionInput(transition.getId())).claim();
            } else {
                log.warn("Issue cannot be transitioned to {}.  Transition is not applicable to issue {}.  Available transitions: {}",
                        transitionName, bugId, transitions.toString());
            }
        } catch (RestClientException e) {
            log.error("There was a problem transitioning issue {}. ", bugId, e);
            throw new JiraClientException();
        }
        return issue;
    }

    private Issue transitionCloseIssue(String bugId, String transitionName, BugTracker bt, boolean falsePositive) throws JiraClientException {
        Issue issue;
        try {
            issue = this.issueClient.getIssue(bugId).claim();

            URI transitionURI = issue.getTransitionsUri();
            final Iterable<Transition> transitions = this.issueClient.getTransitions(transitionURI).claim();
            final Transition transition = getTransitionByName(transitions, transitionName);
            if (transition != null) {
                //No input for transition
                if (ScanUtils.empty(bt.getCloseTransitionField())
                        && ScanUtils.empty(bt.getCloseTransitionValue())) {
                    this.issueClient.transition(issue.getTransitionsUri(), new TransitionInput(transition.getId())).claim();
                }//Input required for transition
                else {
                    String transitionValue = bt.getCloseTransitionValue();
                    if (falsePositive && !ScanUtils.empty(jiraProperties.getCloseFalsePositiveTransitionValue())) { //Allow for a separate resolution status if any of the issues are false positive
                        transitionValue = jiraProperties.getCloseFalsePositiveTransitionValue();  //TODO add to bt?
                    }
                    this.issueClient.transition(issue.getTransitionsUri(), new TransitionInput(transition.getId(),
                            Collections.singletonList(new FieldInput(bt.getCloseTransitionField(), ComplexIssueInputFieldValue.with("name", transitionValue))))).claim();
                }
            } else {
                log.warn("Issue cannot't be transitioned to {}.  Transition is not applicable to issue {}.  Available transitions: {}",
                        transitionName, bugId, transitions.toString());
            }
        } catch (RestClientException e) {
            log.error("There was a problem transitioning issue {}. ", bugId, e);
            throw new JiraClientException("");
        }
        return issue;
    }

    private User getAssignee(String assignee) {
        return client.getUserClient().getUser(assignee).claim();
    }

    private void addCommentToBug(String bugId, String comment) {
        try {
            Issue issue = this.issueClient.getIssue(bugId).claim();
            this.issueClient.addComment(issue.getCommentsUri(), Comment.valueOf(comment)).claim();
        } catch (RestClientException e) {
            log.error("Error occurred", e);
        }
    }

    /**
     * Returns Jira Transition object based on transition name from a list of
     * transitions
     */
    private Transition getTransitionByName(Iterable<Transition> transitions, String transitionName) {
        for (Transition transition : transitions) {
            if (transition.getName().equals(transitionName)) {
                return transition;
            }
        }
        return null;
    }

    private void loadCustomFields(String jiraProject, String issueType, boolean force) {
        log.debug("Loading all custom fields");
        Map<String, String> fields = new HashMap<>();
        String customFieldKey = jiraProject.concat(issueType);
        if(!force && this.customFields.containsKey(customFieldKey)){
            return;
        }
        GetCreateIssueMetadataOptions options;
        options = new GetCreateIssueMetadataOptionsBuilder()
                .withExpandedIssueTypesFields()
                .withProjectKeys(jiraProject)
                .withIssueTypeNames(issueType)
                .build();
        Iterable<CimProject> metadata = this.issueClient.getCreateIssueMetadata(options).claim();
        CimProject cim = metadata.iterator().next();
        cim.getIssueTypes().forEach(issueTypes ->
                issueTypes.getFields().forEach((id, value) -> {
                    String name = value.getName();
                    fields.put(name, id);
                })
        );
        this.customFields.put(customFieldKey, fields);
    }

    private String getCustomFieldByName(String jiraProject, String issueType, String fieldName) {
        log.debug("Getting custom field {}", fieldName);
        //TODO logic for forcing a refresh of custom fields
        Map<String, String> fields = this.customFields.get(jiraProject.concat(issueType));
        if(!fields.isEmpty()){
            return fields.get(fieldName);
        }
        return null;
    }

    public void getCustomFields() {
        for (Field p1 : this.metaClient.getFields().claim()) {
            log.info(p1.toString());
        }
    }

    private Map<String, Issue> getJiraIssueMap(List<Issue> issues) {
        Map<String, Issue> jiraMap = new HashMap<>();
        for (Issue issue : issues) {
            jiraMap.put(issue.getSummary(), issue);
        }
        return jiraMap;
    }

    private Map<String, ScanResults.XIssue> getIssueMap(ScanResults results, ScanRequest request) {
        List<ScanResults.XIssue> issues = new ArrayList<>();

        Optional.ofNullable(results.getScaResults()).ifPresent( s -> {
            List<ScanResults.XIssue> scaIssues = ScanUtils.scaToXIssues(s);
            issues.addAll(scaIssues);
        });

        Optional.ofNullable(results.getXIssues()).ifPresent(i ->
                issues.addAll(results.getXIssues())
        );

        String issuePrefix = Optional.ofNullable(jiraProperties.getIssuePrefix()).orElse("");
        String issuePostfix = Optional.ofNullable(jiraProperties.getIssuePostfix()).orElse("");

        Map<String, ScanResults.XIssue> map = new HashMap<>();

        boolean useBranch = isUseBranch(request);
        for (ScanResults.XIssue issue : issues) {
            String key;
            if (useBranch) {
                key = issue.getScaDetails() == null
                        ? String.format(JIRA_ISSUE_KEY, issuePrefix, issue.getVulnerability(), issue.getFilename(), request.getBranch(), issuePostfix)
                        : String.format(JIRA_ISSUE_KEY_3, issuePrefix, issue.getScaDetails().get(0).getFinding().getId(),
                        issue.getScaDetails().get(0).getFinding().getSeverity(), issue.getScaDetails().get(0).getFinding().getScore(),
                        issue.getScaDetails().get(0).getVulnerabilityPackage().getName(),
                        issue.getScaDetails().get(0).getVulnerabilityPackage().getVersion(), request.getBranch(), issuePostfix);
            } else {
                key = String.format(JIRA_ISSUE_KEY_2, issuePrefix, issue.getVulnerability(), issue.getFilename(), issuePostfix);
            }
            map.put(key, issue);
        }
        return map;
    }

    private String getBody(ScanResults.XIssue issue, ScanRequest request, String fileUrl) {
        StringBuilder body = new StringBuilder();
        Optional.ofNullable(jiraProperties.getDescriptionPrefix())
                .ifPresent(body::append);

        boolean useBranch = isUseBranch(request);

        if (useBranch) {
            if (Optional.ofNullable(issue.getScaDetails()).isPresent() ) {
                issue.getScaDetails().stream().findAny().ifPresent(any -> {
                    body.append(any.getFinding().getDescription()).append(ScanUtils.CRLF).append(ScanUtils.CRLF);
                    body.append(String.format(JIRA_ISSUE_BODY_3, any.getFinding().getSeverity(), any.getVulnerabilityPackage().getName(), request.getBranch())).append(ScanUtils.CRLF).append(ScanUtils.CRLF);
                });
            } else {
                body.append(String.format(JIRA_ISSUE_BODY, issue.getVulnerability(), issue.getFilename(), request.getBranch())).append(ScanUtils.CRLF).append(ScanUtils.CRLF);
            }

        } else {
            body.append(String.format(JIRA_ISSUE_BODY_2, issue.getVulnerability(), issue.getFilename())).append(ScanUtils.CRLF).append(ScanUtils.CRLF);
        }
        Optional.ofNullable(issue.getDescription())
                .ifPresent(d -> body.append(d.trim()).append(ScanUtils.CRLF).append(ScanUtils.CRLF));

        Map<String, String> displayedParametersMap = new LinkedHashMap <>();

        displayedParametersMap.put("*Namespace:* ", request.getNamespace());
        displayedParametersMap.put("*Repository:* ", request.getRepoName());
        displayedParametersMap.put("*Branch:* ", request.getBranch());
        displayedParametersMap.put("*Repository Url:* ", request.getRepoUrl());
        displayedParametersMap.put("*Application:* ", request.getApplication());
        displayedParametersMap.put("*Cx-Project:* ", request.getProject());
        displayedParametersMap.put("*Cx-Team:* ", request.getTeam());
        displayedParametersMap.put("*Severity:* ", issue.getSeverity());
        displayedParametersMap.put("*CWE:* ", issue.getCwe());
        displayedParametersMap.forEach((k, v ) -> {
            if (!ScanUtils.empty(v)) {
                body.append(k).append(v).append(ScanUtils.CRLF);
            }
        });

        body.append(ScanUtils.CRLF)
                .append("*Addition Info*").append(ScanUtils.CRLF)
                .append("----").append(ScanUtils.CRLF);

        if (!ScanUtils.empty(issue.getLink()) ) {
            body.append("[Checkmarx|").append(issue.getLink()).append("]").append(ScanUtils.CRLF);
        }
        if (!ScanUtils.anyEmpty(issue.getCwe(), flowProperties.getMitreUrl())) {
            body.append("[Mitre Details|").append(String.format(flowProperties.getMitreUrl(), issue.getCwe())).append("]").append(ScanUtils.CRLF);
        }
        if (!ScanUtils.empty(flowProperties.getCodebashUrl())) {
            body.append("[Training|").append(flowProperties.getCodebashUrl()).append("]").append(ScanUtils.CRLF);
        }
        if (!ScanUtils.empty(flowProperties.getWikiUrl())) {
            body.append("[Guidance|").append(flowProperties.getWikiUrl()).append("]").append(ScanUtils.CRLF);
        }
        if (issue.getDetails() != null && !issue.getDetails().isEmpty()) {
            if (issue.getDetails().entrySet().stream().anyMatch(x -> x.getKey() != null && x.getValue() != null && !x.getValue().isFalsePositive())) {
                body.append("Lines: ");
            }
            String lines = "#lines-";
            issue.getDetails().entrySet().stream()
                    .filter(x -> x.getKey() != null && x.getValue() != null && !x.getValue().isFalsePositive())
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry ->
                            isFileUrlEmpty(request, fileUrl, body, lines, entry)
                    );

            if (flowProperties.isListFalsePositives()) {//List the false positives / not exploitable
                body.append(ScanUtils.CRLF);
                if (issue.getDetails().entrySet().stream().anyMatch(x -> x.getKey() != null && x.getValue() != null && x.getValue().isFalsePositive())) {
                    body.append("Lines Marked Not Exploitable: ");
                }
                issue.getDetails().entrySet().stream()
                        .filter(x -> x.getKey() != null && x.getValue() != null && x.getValue().isFalsePositive())
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry ->
                                isFileUrlEmpty(request, fileUrl, body, lines, entry)
                        );
            }
            body.append(ScanUtils.CRLF).append(ScanUtils.CRLF);
            issue.getDetails().entrySet().stream()
                    .filter(x -> x.getKey() != null && x.getValue() != null && !x.getValue().isFalsePositive())
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        if (!ScanUtils.empty(entry.getValue().getCodeSnippet())) {
                            body.append("----").append(ScanUtils.CRLF);
                            if (!ScanUtils.empty(fileUrl)) {
                                String line = "[Line #";
                                if (request.getRepoType().equals(ScanRequest.Repository.BITBUCKETSERVER)) {
                                    body.append(line).append(entry.getKey()).append(":|").append(fileUrl).append("#").append(entry.getKey()).append("]").append(ScanUtils.CRLF);
                                } else if (request.getRepoType().equals(ScanRequest.Repository.BITBUCKET)) { //BB Cloud
                                    body.append(line).append(entry.getKey()).append(":|").append(fileUrl).append(lines).append(entry.getKey()).append("]").append(ScanUtils.CRLF);
                                } else {
                                    body.append(line).append(entry.getKey()).append(":|").append(fileUrl).append("#L").append(entry.getKey()).append("]").append(ScanUtils.CRLF);
                                }
                            } else {
                                body.append("Line #").append(entry.getKey()).append(ScanUtils.CRLF);
                            }
                            body.append("{code}").append(ScanUtils.CRLF);
                            body.append(entry.getValue().getCodeSnippet()).append(ScanUtils.CRLF);
                            body.append("{code}").append(ScanUtils.CRLF);
                        }
                    });
            body.append("----").append(ScanUtils.CRLF);
        }

        if (issue.getOsaDetails() != null) {
            for (ScanResults.OsaDetails o : issue.getOsaDetails()) {
                body.append(ScanUtils.CRLF);
                if (!ScanUtils.empty(o.getCve())) {
                    body.append("h3.").append(o.getCve()).append(ScanUtils.CRLF);
                }
                body.append("{noformat}");
                if (!ScanUtils.empty(o.getSeverity())) {
                    body.append("Severity: ").append(o.getSeverity()).append(ScanUtils.CRLF);
                }
                if (!ScanUtils.empty(o.getVersion())) {
                    body.append("Version: ").append(o.getVersion()).append(ScanUtils.CRLF);
                }
                if (!ScanUtils.empty(o.getDescription())) {
                    body.append("Description: ").append(o.getDescription()).append(ScanUtils.CRLF);
                }
                if (!ScanUtils.empty(o.getRecommendation())) {
                    body.append("Recommendation: ").append(o.getRecommendation()).append(ScanUtils.CRLF);
                }
                if (!ScanUtils.empty(o.getUrl())) {
                    body.append("URL: ").append(o.getUrl());
                }
                body.append("{noformat}");
                body.append(ScanUtils.CRLF);
            }
        }

        Optional.ofNullable(issue.getScaDetails()).ifPresent(s -> {
            int count = issue.getScaDetails().size();
            Map<String, String> scaDetailsMap = new HashMap<>();
            ScanResults.ScaDetails scaDetails = s.stream().findAny().get();

            scaDetailsMap.put("Vulnerability ID", scaDetails.getFinding().getId());
            scaDetailsMap.put("Package Name", scaDetails.getVulnerabilityPackage().getName());
            scaDetailsMap.put("Severity", scaDetails.getFinding().getSeverity().name());
            scaDetailsMap.put("CVSS Score", String.valueOf(scaDetails.getFinding().getScore()));
            scaDetailsMap.put("Publish Date", scaDetails.getFinding().getPublishDate());
            scaDetailsMap.put("Current Version", scaDetails.getVulnerabilityPackage().getVersion());
            scaDetailsMap.put("Recommended version", scaDetails.getFinding().getRecommendations());
            scaDetailsMap.put("Vulnerabilities Count", Integer.toString(count));

            scaDetailsMap.forEach((key, value) ->
                    body.append(key).append(": ").append(value).append(ScanUtils.CRLF)
            );
            String findingLink = ScanUtils.constructVulnerabilityUrl(scaDetails.getVulnerabilityLink(), scaDetails.getFinding());
            body.append("[Link To SCA](").append(findingLink).append(")").append(ScanUtils.CRLF).append(ScanUtils.CRLF);
            body.append("[Reference – NVD link](").append("https://nvd.nist.gov/vuln/detail/").append(scaDetails.getFinding().getCveName()).append(")").append(ScanUtils.CRLF).append(ScanUtils.CRLF);
        });

        if (!ScanUtils.empty(jiraProperties.getDescriptionPostfix())) {
            body.append(jiraProperties.getDescriptionPostfix());
        }
        return StringUtils.truncate(body.toString(), JiraConstants.JIRA_MAX_DESCRIPTION);
    }

    private void isFileUrlEmpty(ScanRequest request, String fileUrl, StringBuilder body, String lines, Map.Entry<Integer, ScanResults.IssueDetails> entry) {
        if (!ScanUtils.empty(fileUrl)) {
            if (request.getRepoType().equals(ScanRequest.Repository.BITBUCKETSERVER)) {
                body.append("[").append(entry.getKey()).append("|").append(fileUrl).append("#").append(entry.getKey()).append("] ");
            } else if (request.getRepoType().equals(ScanRequest.Repository.BITBUCKET)) { //BB Cloud
                body.append("[").append(entry.getKey()).append("|").append(fileUrl).append(lines).append(entry.getKey()).append("] ");
            } else {
                body.append("[").append(entry.getKey()).append("|").append(fileUrl).append("#L").append(entry.getKey()).append("] ");
            }
        } else {
            body.append(entry.getKey()).append(" ");
        }
    }

    Map<String, List<String>> process(ScanResults results, ScanRequest request, ScanDetails scanDetails) throws JiraClientException {
        Map<String, ScanResults.XIssue> map;
        Map<String, Issue> jiraMap;
        List<Issue> issuesParent;
        List<Issue> issuesGrandParent;
        List<String> newIssues = new ArrayList<>();
        List<String> updatedIssues = new ArrayList<>();
        List<String> closedIssues = new ArrayList<>();

        getAndModifyRequestApplication(request);
        loadCustomFields(request.getBugTracker().getProjectKey(), request.getBugTracker().getIssueType(), false);
        if (this.jiraProperties.isChild()) {
            ScanRequest parent = new ScanRequest(request);
            ScanRequest grandparent = new ScanRequest(request);
            BugTracker bugTracker;
            bugTracker = parent.getBugTracker();
            bugTracker.setProjectKey(parentUrl);
            parent.setBugTracker(bugTracker);
            issuesParent = this.getIssues(parent);
            if (grandParentUrl.length() == 0) {
                 log.info("Grandparent field is empty");
                issuesGrandParent = null;
            } else {
                BugTracker bugTrackerGrandParenet;
                bugTrackerGrandParenet = grandparent.getBugTracker();
                bugTrackerGrandParenet.setProjectKey(grandParentUrl);
                grandparent.setBugTracker(bugTrackerGrandParenet);
                issuesGrandParent = this.getIssues(grandparent);
            }
        } else {
            issuesParent = null;
            issuesGrandParent = null;
        }

        log.info("Processing Results and publishing findings to Jira");

        map = this.getIssueMap(results, request);
        setMapWithScanResults(map, nonPublishedScanResultsMap);
        jiraMap = this.getJiraIssueMap(this.getIssues(request));

        for (Map.Entry<String, ScanResults.XIssue> xIssue : map.entrySet()) {
            String issueCurrentKey = xIssue.getKey();
            try {
                ScanResults.XIssue currentIssue = xIssue.getValue();

                /*Issue already exists -> update and comment*/
                if (jiraMap.containsKey(issueCurrentKey)) {
                    Issue issue = jiraMap.get(issueCurrentKey);
                    if (xIssue.getValue().isAllFalsePositive()) {
                        //All issues are false positive, so issue should be closed
                        log.debug("All issues are false positives");
                        Issue fpIssue;
                        fpIssue = checkForFalsePositiveIssuesInList(request, xIssue, currentIssue, issue);
                        closeIssueInCaseOfIssueIsInOpenState(request, closedIssues, fpIssue);
                    }/*Ignore any with label indicating false positive*/
                    else if (!issue.getLabels().contains(jiraProperties.getFalsePositiveLabel())) {
                        updateIssueAndAddToNewIssuesList(request, updatedIssues, xIssue, currentIssue, issue);
                    } else {
                        log.info("Skipping issue marked as false-positive or has False Positive state with key {}", issueCurrentKey);
                    }
                } else {
                    /*Create the new issue*/
                    if (!currentIssue.isAllFalsePositive() && (!jiraProperties.isChild() || (!parentCheck(issueCurrentKey, issuesParent) && !grandparentCheck(issueCurrentKey, issuesGrandParent)))) {
                        if (jiraProperties.isChild()) {
                            log.info("Issue not found in parent creating issue for child");
                        }
                        createIssueAndAddToNewIssuesList(request, newIssues, xIssue, currentIssue);
                    }
                }
            } catch (RestClientException e) {
                log.error("Error occurred while processing issue with key {}", issueCurrentKey, e);
                throw new JiraClientException();
            }
            log.debug("Issue: {} successfully updated. Removing it from dynamic scan results map", xIssue.getValue());
            nonPublishedScanResultsMap.remove(issueCurrentKey);
        }

        /*Check if an issue exists in Jira but not within results and close if not*/
        closeIssueInCaseNotWithinResults(request, map, jiraMap, closedIssues);

        ImmutableMap<String, List<String>> ticketsMap = ImmutableMap.of(
                JiraConstants.NEW_TICKET, newIssues,
                JiraConstants.UPDATED_TICKET, updatedIssues,
                JiraConstants.CLOSED_TICKET, closedIssues);

        logJiraTickets(request, scanDetails, ticketsMap);

        setCurrentNewIssuesList(newIssues);
        setCurrentUpdatedIssuesList(updatedIssues);
        setCurrentClosedIssuesList(closedIssues);
        
        return ticketsMap;
    }

    public List<String> getCurrentNewIssuesList() {
        return currentNewIssuesList;
    }

    public List<String> getCurrentUpdatedIssuesList() {
        return currentUpdatedIssuesList;
    }

    public List<String> getCurrentClosedIssuesList() {
        return currentClosedIssuesList;
    }

    private void logJiraTickets(ScanRequest request, ScanDetails scanDetails, ImmutableMap<String, List<String>> ticketsMap) {
        if (scanDetails.getScanId() != null) {
            new JiraTicketsReport(scanDetails.getScanId(), request).build(ticketsMap).log();
        } else if (scanDetails.getOsaScanId() != null) {
            new JiraTicketsReport(scanDetails.getOsaScanId(), request).build(ticketsMap).log();
        } else {
            new JiraTicketsReport(request).build(ticketsMap).log();
        }
    }

    private void closeIssueInCaseNotWithinResults(ScanRequest request, Map<String, ScanResults.XIssue> map, Map<String, Issue> jiraMap, List<String> closedIssues) throws JiraClientException {
        for (Map.Entry<String, Issue> jiraIssue : jiraMap.entrySet()) {
            try {
                if (!map.containsKey(jiraIssue.getKey()) && (request.getBugTracker().getOpenStatus().contains(jiraIssue.getValue().getStatus().getName()))) {
                    /*Close the issue*/
                    log.info("Closing issue {} with key {}",jiraIssue.getValue().getKey(), jiraIssue.getKey());
                    this.transitionCloseIssue(jiraIssue.getValue().getKey(),
                            request.getBugTracker().getCloseTransition(), request.getBugTracker(), false); //No false positives
                    closedIssues.add(jiraIssue.getValue().getKey());

                }
            } catch (HttpClientErrorException e) {
                log.error("Error occurred while processing issue {} with key {}",jiraIssue.getValue().getKey(), jiraIssue.getKey(), e);
            }
        }
    }

    private void createIssueAndAddToNewIssuesList(ScanRequest request, List<String> newIssues, Map.Entry<String, ScanResults.XIssue> xIssue, ScanResults.XIssue currentIssue) throws JiraClientException {
        log.debug("Creating new issue with key {}", xIssue.getKey());
        String newIssue = this.createIssue(currentIssue, request);
        newIssues.add(newIssue);
        log.info("New issue created. #{}", newIssue);
    }

    private void updateIssueAndAddToNewIssuesList(ScanRequest request, List<String> updatedIssues, Map.Entry<String, ScanResults.XIssue> xIssue, ScanResults.XIssue currentIssue, Issue issue) throws JiraClientException {
        log.debug("Issue still exists.  Updating issue with key {}", xIssue.getKey());
        Issue updatedIssue = this.updateIssue(issue.getKey(), currentIssue, request);
        if (updatedIssue != null) {
            log.debug("Update completed for issue #{}", updatedIssue.getKey());
            updatedIssues.add(updatedIssue.getKey());
            if (jiraProperties.isUpdateComment() && !ScanUtils.empty(jiraProperties.getUpdateCommentValue())) {
                addCommentToBug(issue.getKey(), jiraProperties.getUpdateCommentValue());
            }
        }
    }

    private void closeIssueInCaseOfIssueIsInOpenState(ScanRequest request, List<String> closedIssues, Issue fpIssue) throws JiraClientException {
        if (request.getBugTracker().getOpenStatus().contains(fpIssue.getStatus().getName())) { //If the status is of open state, close it
            /*Close the issue*/
            log.info("Closing issue with key {}", fpIssue.getKey());
            this.transitionCloseIssue(fpIssue.getKey(), request.getBugTracker().getCloseTransition(), request.getBugTracker(), true);
            closedIssues.add(fpIssue.getKey());
        }
    }

    private Issue checkForFalsePositiveIssuesInList(ScanRequest request, Map.Entry<String, ScanResults.XIssue> xIssue, ScanResults.XIssue currentIssue, Issue issue) throws JiraClientException {
        Issue fpIssue;
        if (flowProperties.isListFalsePositives()) { //Update the ticket if flag is set
            log.debug("Issue is being updated to reflect false positive references.  Updating issue with key {}", xIssue.getKey());
            fpIssue = this.updateIssue(issue.getKey(), currentIssue, request);
        } else { //otherwise simply get a reference to the issue
            fpIssue = this.getIssue(issue.getKey());
        }
        return fpIssue;
    }

    private void getAndModifyRequestApplication(ScanRequest request) {
        String application = request.getApplication();
        if (!ScanUtils.empty(application)) {
            application = application.replaceAll("[^a-zA-Z0-9-_.+]+", "_");
            request.setApplication(application);
        }
    }

    Map<String, ScanResults.XIssue> getNonPublishedScanResults() {
        return nonPublishedScanResultsMap;
    }
    
    boolean parentCheck(String key, List<Issue> issues) {
        if (issues != null){
            Map<String, Issue> jiraMap;
            jiraMap = this.getJiraIssueMap(issues);
            if (this.jiraProperties.isChild() && (jiraMap.containsKey(key))) {
                log.info("Issue ({}) found in parent ({}) not creating issue for child issue", jiraMap.get(key).getKey(), parentUrl);
                return true;
            }
            return false;
        }
        return false;
    }
    
    boolean grandparentCheck(String key, List<Issue> issues) {
        if (issues != null){
            Map<String, Issue> jiraMap;
            jiraMap = this.getJiraIssueMap(issues);
            if (this.jiraProperties.isChild() && (jiraMap.containsKey(key))) {
                log.info("Issue ({}) found in grandParent ({}) not creating issue for child issue", jiraMap.get(key).getKey(), grandParentUrl);
                return true;
            }
            return false;
        }
        return false;
    }

    public URI getJiraURI() {
        return jiraURI;
    }

    private void setMapWithScanResults(Map<String, ScanResults.XIssue> sourceMap, Map<String, ScanResults.XIssue> destinationMap) {
        if (sourceMap != null && !sourceMap.isEmpty()) {
            destinationMap.putAll(sourceMap);
        }
    }

    private void setCurrentNewIssuesList(List<String> currentNewIssuesList) {
        this.currentNewIssuesList.clear();
        this.currentNewIssuesList.addAll(currentNewIssuesList);
    }

    private void setCurrentUpdatedIssuesList(List<String> currentUpdatedIssuesList) {
        this.currentUpdatedIssuesList.clear();
        this.currentUpdatedIssuesList.addAll(currentUpdatedIssuesList);
    }

    private void setCurrentClosedIssuesList(List<String> currentClosedIssuesList) {
        this.currentClosedIssuesList.clear();
        this.currentClosedIssuesList.addAll(currentClosedIssuesList);
    }
}
