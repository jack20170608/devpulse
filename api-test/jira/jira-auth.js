const email = request.environment.get("JIRA_EMAIL");
const token = request.environment.get("JIRA_API_TOKEN");
client.global.set("JIRA_BASIC", btoa(`${email}:${token}`));
