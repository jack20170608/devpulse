### What
${what}

### Why
${why}

### Impact
${impact}

### Description
${description}

### SIT
Please provide the steps to reproduce the issue in SIT environment.

<#list sitTestSteps as step>
${step?counter}. ${step}
</#list>

### UAT
Please provide the steps to reproduce the issue in UAT environment.
<#list sitTestSteps as step>
${step?counter}. ${step}
</#list>
