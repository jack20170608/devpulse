### What
Render reproduction code

### Why
Preserve code formatting

### Impact
Affects PVT and root cause instructions

### Description
Include Bash and Java code snippets.

### SIT
Please provide the steps to reproduce the issue in SIT environment.
Step1: Open the issue
Step2: Check the code snippets


### UAT
Please provide the steps to reproduce the issue in UAT environment.
Step1: Open the issue
Step2: Check the code snippets


### PVT
Please refer to the below bash code to reproduce the issue in PVT environment.
```bash
issue="DEV-123"
printf '%s\n' "$issue"
java -version
```

### Root Cause
This is the source code that produce this issue. Please refer to the below code snippet.

```java
public static void main(String[] args) {
    System.out.println("Hello World");
}
```
