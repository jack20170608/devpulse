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
Step${step?counter}. ${step}
</#list>

### UAT
Please provide the steps to reproduce the issue in UAT environment.
<#list uatTestSteps as step>
Step${step?counter}. ${step.description}
${step.pic}
</#list>
