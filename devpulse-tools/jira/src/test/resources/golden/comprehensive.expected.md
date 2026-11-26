[中文](#中文) | [English](#english)

## 中文

### 问题说明

**Issue description disappears after saving**

Saving an issue clears the description when `body` is null.

> Reproduce only in the test project.

### 影响范围

- Issue editing
  - Existing descriptions are lost
  - New issues are unaffected
- Support
  - Manual recovery is required

### 复现步骤

#### SIT

1. Open an existing issue
2. Change the title and save

#### UAT

1. Open the **issue**
   ![Before saving](./before.png)
2. Save and verify the empty description
   ![After saving](./after.png)
3. Record the issue key

### 验证结果

| Environment | Expected | Actual | Status |
| --- | --- | --- | --- |
| SIT | Description retained | Description empty | FAIL |
| UAT | Description retained | Description empty | FAIL |

### PVT

```bash
issue="DEV-123"
printf '%s\n' "$issue"
```

### 根因分析

The update overwrites the existing value without a null check.

```java
if (body == null) {
    issue.setDescription("");
}
```

---

### 参考资料

[Runbook][runbook]

[runbook]: https://example.com/runbook

Request accepted\
Description cleared

## English

### Problem

**Issue description disappears after saving**

Saving an issue clears the description when `body` is null.

> Reproduce only in the test project.

### Impact

- Issue editing
  - Existing descriptions are lost
  - New issues are unaffected
- Support
  - Manual recovery is required

### Reproduction

#### SIT

1. Open an existing issue
2. Change the title and save

#### UAT

1. Open the **issue**
   ![Before saving](./before.png)
2. Save and verify the empty description
   ![After saving](./after.png)
3. Record the issue key

### Verification

| Environment | Expected | Actual | Status |
| --- | --- | --- | --- |
| SIT | Description retained | Description empty | FAIL |
| UAT | Description retained | Description empty | FAIL |

### PVT

```bash
issue="DEV-123"
printf '%s\n' "$issue"
```

### Root cause

The update overwrites the existing value without a null check.

```java
if (body == null) {
    issue.setDescription("");
}
```

---

### References

[Runbook][runbook]

[runbook]: https://example.com/runbook

Request accepted\
Description cleared
