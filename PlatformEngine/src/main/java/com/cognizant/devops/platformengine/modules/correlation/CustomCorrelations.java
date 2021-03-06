package com.cognizant.devops.platformengine.modules.correlation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import com.cognizant.devops.platformcommons.dal.neo4j.GraphDBException;
import com.cognizant.devops.platformcommons.dal.neo4j.GraphResponse;
import com.cognizant.devops.platformcommons.dal.neo4j.Neo4jDBHandler;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class CustomCorrelations {
	private static Logger log = Logger.getLogger(CustomCorrelations.class);
	private static final Pattern p = Pattern.compile("((?<!([A-Z]{1,10})-?)[A-Z]+-\\d+)");
	private int dataBatchSize = 2000;
	
	public void executeCorrelations() {
		updateGitNodesWithJiraKey();
		correlateGitAndJira();
		updateJenkinsDataWihoutCommits();
		correlateGitAndJenkins();
	}
	
	private void updateGitNodesWithJiraKey() {
		Neo4jDBHandler dbHandler = new Neo4jDBHandler();
		try {
			String paginationCypher = "MATCH (n:GIT:RAW) where not exists(n.jiraKeys) return count(n) as count";
			GraphResponse paginationResponse = dbHandler.executeCypherQuery(paginationCypher);
			int resultCount = paginationResponse.getJson().get("results").getAsJsonArray().get(0).getAsJsonObject().get("data")
					.getAsJsonArray().get(0).getAsJsonObject().get("row").getAsJsonArray().get(0).getAsInt();
			while(resultCount > 0) {
				long st = System.currentTimeMillis();
				String gitDataFetchCypher = "MATCH (source:GIT:RAW) where not exists(source.jiraKeys) WITH { uuid: source.uuid, commitId: source.commitId, message: source.message} "
						+ "as data limit "+dataBatchSize+" return collect(data)";
				GraphResponse response = dbHandler.executeCypherQuery(gitDataFetchCypher);
				JsonArray rows = response.getJson().get("results").getAsJsonArray().get(0).getAsJsonObject().get("data")
						.getAsJsonArray().get(0).getAsJsonObject().get("row").getAsJsonArray();
				if(rows.isJsonNull() || rows.size() == 0) {
					break;
				}
				JsonArray dataList = rows.get(0).getAsJsonArray();
				int processedRecords = dataList.size();
				String addJiraKeysCypher = "UNWIND {props} as properties MATCH (source:GIT:RAW {uuid : properties.uuid, commitId: properties.commitId}) "
						+ "set source.jiraKeys = properties.jiraKeys set source.jiraRelAdded = false return count(source)";
				String updateRawLabelCypher = "UNWIND {props} as properties MATCH (source:GIT:RAW {uuid : properties.uuid, commitId: properties.commitId}) "
						+ "remove source:RAW return count(source)";
				List<JsonObject> jiraKeysCypherProps = new ArrayList<JsonObject>();
				List<JsonObject> updateRawLabelCypherProps = new ArrayList<JsonObject>();
				JsonObject data = null;
				for(JsonElement dataElem : dataList) {
					JsonObject dataJson = dataElem.getAsJsonObject();
					JsonElement messageElem = dataJson.get("message");
					if(messageElem.isJsonPrimitive()) {
						String message = messageElem.getAsString();
						JsonArray jiraKeys = new JsonArray();
						while(message.contains("-")) {
							Matcher m = p.matcher(message);
							if(m.find()) {
								jiraKeys.add(m.group());
								message = message.replaceAll(m.group(), "");
							}else {
								break;
							}
						}
						data = new JsonObject();
						data.addProperty("uuid", dataJson.get("uuid").getAsString());
						data.addProperty("commitId", dataJson.get("commitId").getAsString());
						if(jiraKeys.size() > 0) {
							data.add("jiraKeys", jiraKeys);
							jiraKeysCypherProps.add(data);
						}else {
							updateRawLabelCypherProps.add(data);
						}
					}
				}
				if(updateRawLabelCypherProps.size() > 0) {
					JsonObject bulkCreateNodes = dbHandler.bulkCreateNodes(updateRawLabelCypherProps, null, updateRawLabelCypher);
					log.debug(bulkCreateNodes);
				}
				if(jiraKeysCypherProps.size() > 0) {
					JsonObject bulkCreateNodes = dbHandler.bulkCreateNodes(jiraKeysCypherProps, null, addJiraKeysCypher);
					log.debug(bulkCreateNodes);
				}
				resultCount = resultCount - dataBatchSize;
				log.debug("Processed "+processedRecords+" GIT records, time taken: "+(System.currentTimeMillis() - st) + " ms");
			}
		} catch (GraphDBException e) {
			log.error("Unable to extract JIRA keys from Git Commit messages", e);
		}
	}
	
	private void correlateGitAndJira() {
		Neo4jDBHandler dbHandler = new Neo4jDBHandler();
		try {
			//int dataBatchSize = 10;
			String paginationCypher = "MATCH (source:GIT:RAW) where exists(source.jiraKeys) return count(source) as count";
			GraphResponse paginationResponse = dbHandler.executeCypherQuery(paginationCypher);
			int resultCount = paginationResponse.getJson().get("results").getAsJsonArray().get(0).getAsJsonObject().get("data")
					.getAsJsonArray().get(0).getAsJsonObject().get("row").getAsJsonArray().get(0).getAsInt();
			while(resultCount > 0) {
				long st = System.currentTimeMillis();
				String gitDataFetchCypher = "MATCH (source:GIT:RAW) where exists(source.jiraKeys) "
						+ "WITH { uuid: source.uuid, commitId: source.commitId, jiraKeys: source.jiraKeys} as data limit "+dataBatchSize+" return collect(data)";
				GraphResponse response = dbHandler.executeCypherQuery(gitDataFetchCypher);
				JsonArray rows = response.getJson().get("results").getAsJsonArray().get(0).getAsJsonObject().get("data")
						.getAsJsonArray().get(0).getAsJsonObject().get("row").getAsJsonArray();
				if(rows.isJsonNull() || rows.size() == 0) {
					return;
				}
				JsonArray dataJsonArray = rows.get(0).getAsJsonArray();
				int processedRecords = dataJsonArray.size();
				String gitToJiraCorrelationCypher = "UNWIND {props} as properties "
						+ "MATCH (source:GIT:RAW { uuid: properties.uuid, commitId: properties.commitId}) "
						+ "MATCH (destination:JIRA) where destination.key IN properties.jiraKeys "
						+ "CREATE (source) -[r:GIT_COMMIT_WITH_JIRA_KEY]-> (destination) "
						+ "remove source:RAW ";
				List<JsonObject> dataList = new ArrayList<JsonObject>();
				for(JsonElement data : dataJsonArray) {
					dataList.add(data.getAsJsonObject());
				}
				JsonObject gitJiraCorrelationResponse = dbHandler.bulkCreateNodes(dataList, null, gitToJiraCorrelationCypher);
				log.debug(gitJiraCorrelationResponse);
				resultCount = resultCount - dataBatchSize;
				log.debug("Processed "+processedRecords+" GIT records, time taken: "+(System.currentTimeMillis() - st) + " ms");
			}
		} catch (GraphDBException e) {
			log.error(e);
		}
	}
	
	private void updateJenkinsDataWihoutCommits() {
		Neo4jDBHandler dbHandler = new Neo4jDBHandler();
		try {
			boolean processJenkinsNodes = true;
			while(processJenkinsNodes) {
				long st = System.currentTimeMillis();
				String jenkinsUpdateCypher = "MATCH (source:RAW:JENKINS) where not (exists(source.lastBuiltRevision) OR exists(source.scmCommitId)) "
						+ "WITH source limit "+dataBatchSize+" "
						+ "remove source:RAW "
						+ "return count(source)";
				GraphResponse response = dbHandler.executeCypherQuery(jenkinsUpdateCypher);
				JsonArray rows = response.getJson().get("results").getAsJsonArray().get(0).getAsJsonObject().get("data")
						.getAsJsonArray().get(0).getAsJsonObject().get("row").getAsJsonArray();
				if(rows.isJsonNull() || rows.size() == 0) {
					return;
				}
				int processedRecords = rows.get(0).getAsInt();
				log.debug("Processed "+processedRecords+" JENKINS records, time taken: "+(System.currentTimeMillis() - st) + " ms");
				if(processedRecords == 0) {
					processJenkinsNodes = false;
					break;
				}
			}
		} catch (GraphDBException e) {
			log.error("Unable to remove RAW label from Jenkins nodes.", e);
		}
	}
	
	private void correlateGitAndJenkins() {
		Neo4jDBHandler dbHandler = new Neo4jDBHandler();
		try {
			String paginationCypher = "MATCH (source:RAW:JENKINS) return count(distinct source) as count";
			GraphResponse paginationResponse = dbHandler.executeCypherQuery(paginationCypher);
			int resultCount = paginationResponse.getJson().get("results").getAsJsonArray().get(0).getAsJsonObject().get("data")
					.getAsJsonArray().get(0).getAsJsonObject().get("row").getAsJsonArray().get(0).getAsInt();
			while(resultCount > 0) {
				long st = System.currentTimeMillis();
				String gitDataFetchCypher = "MATCH (source:RAW:JENKINS) WITH source limit "+dataBatchSize+" "
						+ "WITH source, coalesce(source.lastBuiltRevision, \"\") + \",\" + coalesce(source.scmCommitId, \"\") as commitId  "
						+ "WITH source.uuid as uuid, split(commitId, \",\") as commits unwind commits as commit WITH uuid, trim(commit) as commit where commit is not null "
						+ "WITH distinct uuid, collect(distinct commit) as commits WITH { uuid : uuid, commitId: commits} as data "
						+ "return collect(data) as data";
				GraphResponse response = dbHandler.executeCypherQuery(gitDataFetchCypher);
				JsonArray rows = response.getJson().get("results").getAsJsonArray().get(0).getAsJsonObject().get("data")
						.getAsJsonArray().get(0).getAsJsonObject().get("row").getAsJsonArray();
				if(rows.isJsonNull() || rows.size() == 0) {
					return;
				}
				JsonArray dataArray = rows.get(0).getAsJsonArray();
				String jenkinsGitCorrelationCypher = "UNWIND {props} as properties "
						+ "MATCH (source:RAW:JENKINS { uuid: properties.uuid}) "
						+ "MATCH (destination:GIT) where destination.commitId IN properties.commitId "
						+ "CREATE (source) <-[r:JENKINS_TRIGGERED_BY_GIT_COMMIT]- (destination) "
						+ "remove source:RAW  return count(distinct source) as count";
				List<JsonObject> dataList = new ArrayList<JsonObject>();
				for(JsonElement data : dataArray) {
					dataList.add(data.getAsJsonObject());
				}
				JsonObject gitJiraCorrelationResponse = dbHandler.bulkCreateNodes(dataList, null, jenkinsGitCorrelationCypher);
				log.debug(gitJiraCorrelationResponse);
				int processedRecords = gitJiraCorrelationResponse.get("response").getAsJsonObject()
											.get("results").getAsJsonArray().get(0).getAsJsonObject()
											.get("data").getAsJsonArray().get(0).getAsJsonObject()
											.get("row").getAsInt();
				resultCount = resultCount - processedRecords;
				log.debug("Processed "+processedRecords+" GIT records, time taken: "+(System.currentTimeMillis() - st) + " ms");
				System.out.println("Processed "+processedRecords+" GIT records, time taken: "+(System.currentTimeMillis() - st) + " ms");
				if(processedRecords == 0) {
					break;
				}
			}
		} catch (GraphDBException e) {
			log.error("Unable to execute correlations between Jenkins and JIRA", e);
		}
	}
}
 