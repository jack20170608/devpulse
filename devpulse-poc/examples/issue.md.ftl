# ${title}

[中文](#中文) | [English](#english)

## 中文

### 描述

${description}

### 复现步骤

<#list steps as step>
${step?counter}. ${step}
</#list>

<#if includeAcceptance>
### 验收标准

<#list acceptance as item>
- [ ] ${item}
</#list>
</#if>

## English

### Description

${description}

### Steps to reproduce

<#list steps as step>
${step?counter}. ${step}
</#list>

<#if includeAcceptance>
### Acceptance criteria

<#list acceptance as item>
- [ ] ${item}
</#list>
</#if>
