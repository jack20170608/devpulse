[中文](#中文) | [English](#english)

<#macro report summary impact reproduction verification rootCause references>
### ${summary}

**${title}**

${description}

> ${warning}

### ${impact}

<#list impacts as item>
- ${item.area}
<#list item.details as detail>
  - ${detail}
</#list>
</#list>

### ${reproduction}

#### SIT

<#list sitSteps as step>
${step?counter}. ${step}
</#list>

#### UAT

<#list uatSteps as step>
${step?counter}. ${step.description}
<#if step.screenshot?has_content>
   ![${step.caption}](${step.screenshot})
</#if>
</#list>

### ${verification}

| Environment | Expected | Actual | Status |
| --- | --- | --- | --- |
<#list results as result>
| ${result.environment} | ${result.expected} | ${result.actual} | ${result.status} |
</#list>

### PVT

```bash
${pvtCode}
```

### ${rootCause}

${rootCauseDescription}

```java
${rootCauseCode}
```

---

### ${references}

[Runbook][runbook]

[runbook]: ${runbookUrl}

<#list logs as line>
${line}<#if line?has_next>\</#if>
</#list>
</#macro>
## 中文

<@report summary="问题说明" impact="影响范围" reproduction="复现步骤"
    verification="验证结果" rootCause="根因分析" references="参考资料" />

## English

<@report summary="Problem" impact="Impact" reproduction="Reproduction"
    verification="Verification" rootCause="Root cause" references="References" />
