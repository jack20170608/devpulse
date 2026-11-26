[中文](#中文) | [English](#english)

# Jira CLI 生命周期命令集 / Jira CLI lifecycle commands

## 中文

按顺序手工执行的完整 Jira 生命周期命令，覆盖初始化、只读查询、创建、编辑、流转、协作、附件、Sprint/Epic 归属及清理。这里不做断言，请人工检查每步输出。命令会写入真实 Jira，请使用测试项目。

### 0. 准备

在 Git Bash 中从仓库根目录执行。先构建一次，再定义 `jira` 函数，并在临时目录中运行，避免 `.jira` 和导出文件进入仓库。

```bash
bash build.sh jira --quiet
JAR="$PWD/devpulse-tools/jira/target/devpulse-jira.jar" && jira() { java -jar "$JAR" "$@"; }

export JIRA_API_TOKEN='your-api-token'
export JIRA_URL='https://your-site.atlassian.net'
export JIRA_EMAIL='you@example.com'
export PROJECT='SPT'

WORK="$(mktemp -d)"
cd "$WORK"
jira --version
```

### 1. 初始化与连接

```bash
jira config init --no-input --url "$JIRA_URL" --mode cloud --email "$JIRA_EMAIL" -p "$PROJECT"
jira init --no-input --language zh        # 保留已有配置，补全 .jira/profiles/default
jira config show
jira --machine config show
jira config set profile default           # 原位修改，保留注释
jira config unset profile
cat ".jira/local_jira_config.conf"
jira config verify                        # GET myself
jira --machine config verify
jira env refresh                          # 写入 .jira/environment/$PROJECT.json
jira env show
jira --machine env show -p "$PROJECT"
ls .jira/profiles/default/jira-requirements .jira/environment
jira me
```

### 2. 只读目录查询

```bash
jira project list --limit 10
jira board list
jira board list --type scrum
jira release list
jira sprint list --state active,future
jira epic list --limit 10
jira issue list --limit 10
jira issue list -a me -s ~Done --order-by updated
jira issue list -q "project = $PROJECT AND created >= -7d ORDER BY created DESC" --plain
```

### 3. Profile 与 Skill

```bash
jira profile list
jira profile list --presets
jira profile init team-a --preset story --language zh
jira profile init bug-team --preset bug --language zh
jira profile list
mkdir -p .claude/skills
jira skill export --language zh --profile default --no-input
jira skill export --output skill-team-a --language zh --profile team-a --no-input
ls .claude/skills/jira-requirements .claude/skills/jira-requirements/references .claude/skills/jira-requirements/templates
# 如需导出到用户 home：jira skill export -g --language zh --profile default --no-input
```

### 4. 创建 Epic 与 Issue

创建命令输出新 key，请手动填入下面的变量。

```bash
jira epic create --no-input -n "CLI 生命周期测试 Epic" -s "[CLI TEST] Lifecycle epic" -l cli-test
export EPIC='SPT-100'                    # 替换为上一步输出的 key

jira issue template --file story.md      # 离线生成模板，可按需编辑
jira issue create --no-input --file story.md -s "[CLI TEST] Lifecycle story" -t Task -y Medium -l cli-test --epic "$EPIC"
export KEY='SPT-101'                     # 替换为上一步输出的 key

jira issue create --no-input -s "[CLI TEST] Second issue" -t Task -b "用于链接与 Epic 移动测试" -l cli-test
export KEY2='SPT-102'                    # 替换为上一步输出的 key
```

### 5. 查看与导出

```bash
jira issue view "$KEY"
jira issue view "$KEY" --fields summary,status,epic
jira issue view "$KEY" --format text --comments 5
jira issue view "$KEY" --raw > "$KEY.json"
jira open "$KEY" --print
```

### 6. 编辑

```bash
jira issue edit "$KEY" --no-input -s "[CLI TEST] Lifecycle story (edited)" -y High
jira issue edit "$KEY" --no-input -l extra-label
jira issue edit "$KEY" --no-input --label=-extra-label
printf '## 背景\n\n通过 stdin 更新描述。\n' | jira issue edit "$KEY" --no-input -b -

jira export "$KEY" --file "$KEY.md"      # 导出可编辑字段与无损描述
# 手工修改 $KEY.md 的 summary 或正文后：
jira update --file "$KEY.md"
jira issue view "$KEY"
```

### 7. 指派与流转

```bash
jira issue assign "$KEY" me --no-input
jira transitions "$KEY"
jira issue move "$KEY" "In Progress" --no-input --comment "开始处理"
jira issue view "$KEY" --fields status,assignee
```

### 8. 评论与工时

```bash
jira issue comment add "$KEY" "第一条评论" --no-input
printf '**Markdown** 评论，来自文件。\n' > comment.md
jira comment "$KEY" --file comment.md
jira issue view "$KEY" --raw --comments 5   # 记下评论 id

export COMMENT_ID='10001'                # 替换为评论 id
jira comment-export "$KEY" "$COMMENT_ID" --file comment-export.md
# 手工修改 comment-export.md 后：
jira comment-update "$KEY" "$COMMENT_ID" --file comment-export.md

jira issue worklog add "$KEY" "1h 30m" --comment "CLI 生命周期测试" --no-input
```

### 9. 附件（测试证据）

```bash
printf 'test evidence\n' > evidence.txt
jira issue attachment add "$KEY" --file evidence.txt
jira issue attachment list "$KEY"
export ATTACHMENT_ID='20001'             # 替换为附件 id
jira issue attachment delete "$KEY" "$ATTACHMENT_ID"
jira issue attachment list "$KEY"
```

### 10. 链接

```bash
jira issue link "$KEY" "$KEY2" Relates --no-input
jira issue link remote "$KEY" "https://example.com/build/1" "CI build #1" --no-input
jira issue view "$KEY" --format text
jira issue unlink "$KEY" "$KEY2" --no-input
```

### 11. Epic 与 Sprint 归属

```bash
jira epic add "$EPIC" "$KEY2" --no-input
jira epic list "$EPIC"
jira epic remove "$KEY2" --no-input

jira sprint list --state active,future
export SPRINT_ID='42'                    # 替换为 active 或 future Sprint id
jira sprint add "$SPRINT_ID" "$KEY" "$KEY2" --no-input
jira sprint list "$SPRINT_ID"
jira sprint list --current
```

### 12. 克隆与完成

```bash
jira issue clone "$KEY" --no-input -s "[CLI TEST] Cloned story" -H "Lifecycle:Clone"
export CLONE='SPT-103'                   # 替换为克隆输出的 key

jira transitions "$KEY"
jira issue move "$KEY" Done --no-input --comment "测试完成"
export TRANSITION_ID='31'                # 可选：按 ID 流转
jira transition "$KEY2" "$TRANSITION_ID"
jira issue list -l cli-test -s Done
```

### 13. 机器协议抽查

```bash
jira --machine issue view "$KEY"
jira --machine issue list -l cli-test --limit 5
jira --machine issue attachment list "$KEY"
jira --machine profile list
```

### 14. 清理

```bash
jira issue delete "$CLONE" --no-input
jira issue delete "$KEY2" --no-input
jira issue delete "$KEY" --no-input --cascade
jira issue delete "$EPIC" --no-input
jira issue list -l cli-test
cd - && rm -rf "$WORK"
```

## English

A complete Jira lifecycle command set for manual execution in order, covering initialization, read-only queries, creation, editing, transitions, collaboration, attachments, Sprint/Epic membership, and cleanup. It has no assertions; inspect each step's output manually. Commands write to a real Jira instance, so use a test project.

### 0. Preparation

Run from the repository root in Git Bash. Build once, define a `jira` function, and work in a temporary directory so `.jira` and exported files stay out of the repository.

```bash
bash build.sh jira --quiet
JAR="$PWD/devpulse-tools/jira/target/devpulse-jira.jar"
jira() { java -jar "$JAR" "$@"; }

export JIRA_API_TOKEN='your-api-token'
export JIRA_URL='https://your-site.atlassian.net'
export JIRA_EMAIL='you@example.com'
export PROJECT='SPT'

WORK="$(mktemp -d)"
cd "$WORK"
jira --version
```

### 1. Initialization and connection

```bash
jira config init --no-input --url "$JIRA_URL" --mode cloud --email "$JIRA_EMAIL" -p "$PROJECT"
jira init --no-input --language en        # keep the configuration; fill in .jira/profiles/default
jira config show
jira --machine config show
jira config set profile default           # in-place edit; comments kept
jira config unset profile
cat ".jira/local_jira_config.conf"
jira config verify                        # GET myself
jira --machine config verify
jira env refresh                          # write .jira/environment/$PROJECT.json
jira env show
jira --machine env show -p "$PROJECT"
ls .jira/profiles/default/jira-requirements .jira/environment
jira me
```

### 2. Read-only catalog queries

```bash
jira project list --limit 10
jira board list
jira board list --type scrum
jira release list
jira sprint list --state active,future
jira epic list --limit 10
jira issue list --limit 10
jira issue list -a me -s ~Done --order-by updated
jira issue list -q "project = $PROJECT AND created >= -7d ORDER BY created DESC" --plain
```

### 3. Profiles and skills

```bash
jira profile list
jira profile list --presets
jira profile init team-a --preset story --language en
jira profile init bug-team --preset bug --language en
jira profile list
mkdir -p .claude/skills
jira skill export --language en --profile default --no-input
jira skill export --output skill-team-a --language en --profile team-a --no-input
ls .claude/skills/jira-requirements .claude/skills/jira-requirements/references .claude/skills/jira-requirements/templates
# To export into the user's home: jira skill export -g --language en --profile default --no-input
```

### 4. Create an epic and issues

Creation commands print the new key; fill in the variables below manually.

```bash
jira epic create --no-input -n "CLI lifecycle test epic" -s "[CLI TEST] Lifecycle epic" -l cli-test
export EPIC='SPT-100'                    # replace with the key printed above

jira issue template --file story.md      # offline template; edit as needed
jira issue create --no-input --file story.md -s "[CLI TEST] Lifecycle story" -t Task -y Medium -l cli-test --epic "$EPIC"
export KEY='SPT-101'                     # replace with the key printed above

jira issue create --no-input -s "[CLI TEST] Second issue" -t Task -b "For link and epic move tests" -l cli-test
export KEY2='SPT-102'                    # replace with the key printed above
```

### 5. View and export

```bash
jira issue view "$KEY"
jira issue view "$KEY" --fields summary,status,epic
jira issue view "$KEY" --format text --comments 5
jira issue view "$KEY" --raw > "$KEY.json"
jira open "$KEY" --print
```

### 6. Edit

```bash
jira issue edit "$KEY" --no-input -s "[CLI TEST] Lifecycle story (edited)" -y High
jira issue edit "$KEY" --no-input -l extra-label
jira issue edit "$KEY" --no-input --label=-extra-label
printf '## Background\n\nDescription updated from stdin.\n' | jira issue edit "$KEY" --no-input -b -

jira export "$KEY" --file "$KEY.md"      # export editable fields and a lossless description
# After manually editing the summary or body in $KEY.md:
jira update --file "$KEY.md"
jira issue view "$KEY"
```

### 7. Assign and transition

```bash
jira issue assign "$KEY" me --no-input
jira transitions "$KEY"
jira issue move "$KEY" "In Progress" --no-input --comment "Work started"
jira issue view "$KEY" --fields status,assignee
```

### 8. Comments and worklogs

```bash
jira issue comment add "$KEY" "First comment" --no-input
printf '**Markdown** comment from a file.\n' > comment.md
jira comment "$KEY" --file comment.md
jira issue view "$KEY" --raw --comments 5   # note a comment id

export COMMENT_ID='10001'                # replace with a comment id
jira comment-export "$KEY" "$COMMENT_ID" --file comment-export.md
# After manually editing comment-export.md:
jira comment-update "$KEY" "$COMMENT_ID" --file comment-export.md

jira issue worklog add "$KEY" "1h 30m" --comment "CLI lifecycle test" --no-input
```

### 9. Attachments (test evidence)

```bash
printf 'test evidence\n' > evidence.txt
jira issue attachment add "$KEY" --file evidence.txt
jira issue attachment list "$KEY"
export ATTACHMENT_ID='20001'             # replace with the attachment id
jira issue attachment delete "$KEY" "$ATTACHMENT_ID"
jira issue attachment list "$KEY"
```

### 10. Links

```bash
jira issue link "$KEY" "$KEY2" Relates --no-input
jira issue link remote "$KEY" "https://example.com/build/1" "CI build #1" --no-input
jira issue view "$KEY" --format text
jira issue unlink "$KEY" "$KEY2" --no-input
```

### 11. Epic and sprint membership

```bash
jira epic add "$EPIC" "$KEY2" --no-input
jira epic list "$EPIC"
jira epic remove "$KEY2" --no-input

jira sprint list --state active,future
export SPRINT_ID='42'                    # replace with an active or future sprint id
jira sprint add "$SPRINT_ID" "$KEY" "$KEY2" --no-input
jira sprint list "$SPRINT_ID"
jira sprint list --current
```

### 12. Clone and complete

```bash
jira issue clone "$KEY" --no-input -s "[CLI TEST] Cloned story" -H "Lifecycle:Clone"
export CLONE='SPT-103'                   # replace with the cloned key

jira transitions "$KEY"
jira issue move "$KEY" Done --no-input --comment "Testing complete"
export TRANSITION_ID='31'                # optional: transition by ID
jira transition "$KEY2" "$TRANSITION_ID"
jira issue list -l cli-test -s Done
```

### 13. Machine protocol spot checks

```bash
jira --machine issue view "$KEY"
jira --machine issue list -l cli-test --limit 5
jira --machine issue attachment list "$KEY"
jira --machine profile list
```

### 14. Cleanup

```bash
jira issue delete "$CLONE" --no-input
jira issue delete "$KEY2" --no-input
jira issue delete "$KEY" --no-input --cascade
jira issue delete "$EPIC" --no-input
jira issue list -l cli-test
cd - && rm -rf "$WORK"
```
