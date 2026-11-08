[中文](#中文) | [English](#english)

## 中文

### 验证结果

| 序号 | 环境 | 预期结果 | 实际结果 | 状态 |
| --- | --- | --- | --- | --- |
<#list results as result>
| ${result?counter} | ${result.environment} | ${result.expected} | ${result.actual} | ${result.status} |
</#list>

## English

### Verification results

| No. | Environment | Expected result | Actual result | Status |
| --- | --- | --- | --- | --- |
<#list results as result>
| ${result?counter} | ${result.environment} | ${result.expected} | ${result.actual} | ${result.status} |
</#list>
