# Bookkeeper App — GitHub Actions 云构建：推送与操作指引

> 本地准备阶段产物。说明如何把工程送进 GitHub 并触发云端构建。
> 本轮只新增/修订 `ci-src/`、`.github/workflows/android.yml`、`CI_PUSH_GUIDE.md`，
> **没有改动任何已有源码、构建脚本或 gradlew**，也没有执行任何写仓库操作。

---

## ⚠️ 0. 首要前提：GitHub 连接器是「只读」授权（已实测）

**不要指望主理人 / 智能体用 MCP 工具帮你推送文件。** 主理人对连接器做了
**4 类写操作、共 5 次真实调用，全部返回 403**（以下为实测结果，不是推测，
列出来是为了避免用户误以为我们漏做了这一步）：

| # | 实测写操作 | 目标 | 结果 |
| --- | --- | --- | --- |
| 1 | `create_repository`（`private=true`） | 新建私有仓库 | `403 Resource not accessible by integration` |
| 2 | `create_repository`（`private=false`） | 新建公开仓库 | `403 Resource not accessible by integration` |
| 3 | `fork_repository` | `octocat/Hello-World` | `403` |
| 4 | `create_or_update_file` | `GT-l/GT-l`（空 profile 仓库） | `403` |
| 5 | `create_or_update_file` | `GT-l/QtSqliteManager`（非空 C++ 仓库） | `403` |

而 `get_me` / `search_repositories` / `get_file_contents` 等**读操作全部正常**。

由此得到三条必须接受的结论：

1. 该连接器**只读**：任何写操作都不可用，**包括 `push_files`**；也无法新建或 fork 仓库。
   所以「主理人随后用 MCP 推送文件」这条路在当前授权下**不存在**。
2. 连接器**没有**查看 Actions 运行日志、也没有下载 artifact 的工具，构建结果只能由
   用户到 GitHub 网页获取。
3. 本机**没有 Git 凭证、没有 `gh` CLI、keychain 没有 github 条目**（已核实：
   `git 2.50.1` 存在但 `credential.helper` 与 `user.name` 均未配置，`gh` 不存在；
   本地仓库已由主理人初始化并提交，`origin` 远端可能已配置，但**从未成功 push 过**）。
   因此 **`git push` 与凭证配置必须由用户本人完成**。

下面给出两条真实可行的路径，**A 为主路径（推荐），B 为备选**。

---

## 1. 两条路径对比（主次分明）

| | **路径 A · 本地 git 仓库 + 用户自己 push（推荐）** | 路径 B · 补写权限后主理人 `push_files`（备选） |
| --- | --- | --- |
| 谁把代码送进 GitHub | **用户本人 `git push`** | 主理人用 MCP 工具 |
| 前置条件 | 用户配好 git 凭证 | 用户把 GitHub App 的 Contents 权限改成 **Read and write** 并重新授权 |
| 推送内容 | 完整 git 仓库（207 个 `.kt`、wrapper jar、workflow 等） | 仅 `ci-src/*.b64` 6 个分块 + workflow |
| 是否依赖 base64 分块 | 否（但仍须一并提交，见 2.1 的注意） | **是**，分块是唯一的源码搬运载体 |
| 能否绕过 403 建仓 | 能（用户自己在网页建） | **不能**，用户大概率仍需自己建空仓库 |
| 复杂度 | 用户跑 3 条命令（或用 GitHub Desktop 点几下） | 用户改授权 + 主理人重测权限 + 7 次文件推送 |

---

## 2. 路径 A · 本地 git 仓库 + 用户自己 push（推荐）

### 2.1 由主理人统一完成的本地仓库初始化

> 依分工，**本地 git 初始化由主理人统一执行**；工程师本轮**没有**运行
> `git init` / `git add` / `git commit`。

主理人**已经完成**初始化（实测：分支 `main`、提交数 `1`、受控文件 `250` 个、
工作区 `clean=YES`、远端 `origin` 已预置）。等价命令为（**无需你再执行**）：

```bash
cd /Users/gt/WorkBuddy/spaces/2026_11_22_11-39-32/bookkeeper-app
git init
git config user.name "GT-l"
git config user.email "GT-l@users.noreply.github.com"
git add -A
git commit -m "Initial commit: Bookkeeper Android app + GitHub Actions CI workflow"
git branch -M main
git remote add origin https://github.com/GT-l/bookkeeper-app.git
```

> 具体 commit SHA 会随后续修订变化，请以 `git log -1 --oneline` 为准，本文不写死。

经核对，现有 `.gitignore` 只忽略 `.gradle/`、`build/`、`*/build/`、`local.properties`、
`.idea/`、`*.iml`、`captures/`、`.DS_Store`，**特意没有忽略 `gradle-wrapper.jar`**
（文件内注释明确写了必须入库），也没有忽略 `.github/` 或 `ci-src/`。
所以 `git add -A` 会把下列内容全部入库：

- `gradle/wrapper/gradle-wrapper.jar` — **二进制文件，git 能正常处理，不需要特殊参数**；
- `gradlew`（**可执行位 `100755`**，实测 `-rwxr-xr-x`）与 `gradlew.bat`；
- `.github/workflows/android.yml`；
- 全部 `app/src/**`（main 198 个 `.kt` + test 9 个 `.kt`）、`app/build.gradle.kts`、
  `app/src/main/AndroidManifest.xml`；
- `gradle/wrapper/gradle-wrapper.properties`、`gradle/libs.versions.toml`、根构建脚本。

> ⚠️ **路径 A 的关键依赖：必须把 `ci-src/` 一起提交（已核实满足）。**
> workflow 的第 2 步 *Restore source tree* 是
> `cat ci-src/src.tar.gz.part*.b64 | base64 -d > src.tar.gz && tar xzf ... --strip-components=1`，
> 且该步带 `set -euo pipefail`。如果仓库里**没有**这 6 个 `.b64` 分块，
> CI 在还原源码树时会因 glob 无匹配而**直接失败**，根本走不到编译。
> 实测 `git ls-files ci-src/` 返回全部 6 个分块，**该依赖已满足**；同时
> `gradle/wrapper/gradle-wrapper.jar` 已入库、`gradlew` 在索引中的模式为 `100755`、
> `.github/workflows/android.yml` 已入库。这一步会把 tar 里的 234 个文件覆盖到
> checkout 之上（内容与 git 中一致，属幂等冗余），且**不会删除**已入库的
> `gradle-wrapper.jar`，不影响构建。今后若有人删掉 `ci-src/`，
> 必须同步调整 workflow 的该步骤，否则首次构建必然失败。

### 2.2 用户需要做的三步

**第 1 步 · 在 GitHub 网页建一个空仓库**

打开：<https://github.com/new?name=bookkeeper-app&visibility=private>

- 仓库名建议 `bookkeeper-app`（下文以 `GT-l/bookkeeper-app` 为例），可见性 `private`；
- **不要**勾选 `Add a README file`；
- **不要**选择 `.gitignore` 模板；
- **不要**选择 license；
- 保持**完全空的仓库**（否则首次 push 会因历史不一致被拒绝）。

**第 2 步 · 配置 git 凭证（三选一）**

| 方案 | 做法 | 适合谁 |
| --- | --- | --- |
| ① **Personal Access Token（classic）** | GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate new token，**勾选 `repo`**；push 时用户名填 GitHub 用户名、密码填 token。可写进 remote URL：`https://<user>:<token>@github.com/GT-l/bookkeeper-app.git`，或交给 credential helper 记住 | 习惯命令行 |
| ② **GitHub Desktop（最省事，推荐给不熟命令行）** | `File` → `Add Existing Repository...` 选中本地 `bookkeeper-app/` → 右上角 `Publish repository`；登录 GitHub 账号即可，token / 凭证由 Desktop 托管，会自动完成等效的 remote 与 push 操作 | **不熟命令行** |
| ③ **SSH key** | 已在本机配好 SSH key 并添加到 GitHub 时，remote 改用 `git@github.com:GT-l/bookkeeper-app.git`，无需 token | 已有 SSH 环境 |

> 本机**没有**上述任何一种（无凭证、无 `gh` CLI、keychain 无 github 条目），
> 所以配置凭证与 push **必须由用户完成**，主理人无法代劳。

**第 3 步 · 推送（初始化与远端已由主理人做完，你只需这一条）**

```bash
cd /Users/gt/WorkBuddy/spaces/2026_11_22_11-39-32/bookkeeper-app
git push -u origin main
```

- **不要**再执行 `git init` / `git add` / `git commit` / `git branch -M main` /
  `git remote add origin`——这些均已完成；重复 `git remote add origin` 会报
  `error: remote origin already exists`；
- 本机实测 `origin` 已预置为 `https://github.com/GT-l/bookkeeper-app.git`
  （`git remote -v` 可确认）。若你在 GitHub 上用了别的仓库名，先改地址再 push：
  `git remote set-url origin https://github.com/GT-l/<你的仓库名>.git`；
- 方案 ③（SSH）改用：`git remote set-url origin git@github.com:GT-l/bookkeeper-app.git`；
- 方案 ② 由 GitHub Desktop 自动完成等效动作，可跳过命令行；
- 若远端不是空的，先在网页仓库里清空，或按 GitHub 提示处理，不要直接 `--force`。

推送到 `main` 的那一刻即**自动触发首次构建**（workflow 的
`on.push.branches = [main]`）；之后也可在 Actions 页用 `workflow_dispatch` 手动重跑。

---

## 3. 路径 B · 用户补齐连接器写权限后，由主理人推送（备选）

如果确实不想自己配 git 凭证，可以先把连接器授权从「只读」提到「可写」：

1. 打开 GitHub → **Settings → Applications**，或 **Developer settings → GitHub Apps**，
   找到 WorkBuddy 使用的那个 GitHub App；
2. 在 **Permissions → Repository permissions → Contents** 把 **Read-only** 改为
   **Read and write** 并保存；
3. 确认 **Repository access / 仓库访问范围**包含目标仓库，或选择 **All repositories**；
   若界面提示需重新授权，请完成重新授权；
   也可以在 **WorkBuddy 的连接器管理**里重新授权该 GitHub 连接器并勾选写权限；
4. 完成后告知主理人，主理人会**重测写权限**；只有重测通过，才会执行推送。

主理人随后按顺序推送 **7 个纯文本文件、合计约 283 KB**：

1. `ci-src/src.tar.gz.part01.b64` … `part06.b64`（前 5 块各 48000 字符、末块 39580
   字符；严格按 01 → 06 的字典序，因为 CI 用 `cat ci-src/src.tar.gz.part*.b64` 拼接）；
2. `.github/workflows/android.yml`（最后推，落地即触发首次构建）。

CI 里 `cat` 拼接 → `base64 -d` → `tar xzf --strip-components=1` 还原 234 个文件，
再由 *Ensure Gradle Wrapper* 步骤补生成被排除的 `gradle-wrapper.jar`，然后编译。

> ⚠️ **即使写权限补齐，仍要如实预期两件事**：
> - `create_repository` 属于账号级 / 安装级权限，**很可能依旧返回 403**，
>   用户**大概率仍需自己按 2.2 第 1 步建好空仓库**；
> - `create_or_update_file` / `push_files` 只能逐文件写**纯文本**，不能推二进制 jar，
>   逐个推 207 个 `.kt` 成本又极高 —— 这正是 base64 分块方案在此处派上用场的原因。

---

## 4. base64 分块的定位（保留，但**不是**主路径）

`ci-src/src.tar.gz.part01.b64` … `part06.b64` 是**路径 B 的搬运载体**，也是
「无法使用 git、只能逐文件写纯文本」时的**备用方案**；**它不是推荐的推送路径**。

- **生成方式**：整个工程 `tar czf`（顶层目录保持 `bookkeeper-app/`）→ `openssl base64 -A`
  编码为单行 279580 字符 → 每 48000 字符切一块 → **6 块**；
- **打包排除**：`docs/`、`deliverables/`、`ci-src/`（避免自包含）、`.git/`、`build/`、
  `app/build/`、`.gradle/`、`*.apk`、`*.tmp`、`gradle/wrapper/gradle-wrapper.jar`；
- **已实测校验**：拼接结果与原 base64 逐字节一致、还原出的 `tar.gz`（209684 字节）
  与原包逐字节一致；`tar tzf | wc -l` = 323（含目录条目），其中纯文件 **234**，
  与源工程按相同排除规则统计的 234 一致；`gradlew`、`app/build.gradle.kts`、
  `app/src/main/AndroidManifest.xml` 全部命中；6 个分块均为纯 ASCII base64、无换行
  与控制字符、可直接放进 JSON 字符串；
- **workflow 的 *Restore source tree* 步骤与上述校验继续保持有效，无需返工**。

> 一处如实说明：分块是在本次指引修订**之前**生成的，tar 内嵌的 `CI_PUSH_GUIDE.md`
> 是旧版本。但 tar 只在 Actions 运行器的临时工作区里解压，**不会回写仓库**，
> 所以仓库中的指引（路径 A 由 git 提交）始终是本文件的最新版，该差异不影响构建。
> 只有在「走路径 B 且希望仓库里也带这份指引」时，才需要额外单独推送本文件，
> 或重新生成一次分块。

---

## 5. 去哪里看结果

- Actions 页：<https://github.com/GT-l/bookkeeper-app/actions>
  （若仓库名不同，把 URL 末尾替换成你的仓库名）
- **构建成功**：打开该次 workflow run → 页面底部 `Artifacts` → 下载
  `bookkeeper-debug-apk`，解压得到 `app-debug.apk`。
- **构建失败**（大概率）：下载 `build-log` artifact 中的 `build.log`，把内容贴回给主理人，
  工程师按日志修源码，用户重新 push 后迭代。
- 另外还会尝试产出 `unit-test-reports`（单元测试报告，该步骤为 `continue-on-error`）。

---

## 6. 诚实的声明

- **本机无法生成 APK**：没有 Android SDK / 没有可用的 Gradle 构建环境，源码从未编译。
- **本机无法查看 CI 结果、无法下载 artifact**：连接器不提供这些工具。
- **本机无法代你 push**：连接器只读（见第 0 节的 403 实测），且本机无 git 凭证、
  无 `gh` CLI、keychain 无 github 条目。
- **198 个 Kotlin 源文件（连同 9 个测试源文件，合计 207 个 `.kt`）从未编译过，
  96 个单元用例从未运行过**。首次构建大概率报错，这属于预期，需要按 `build.log`
  一轮一轮地修。
- 本轮交付仅覆盖「云端构建出 APK」路径的**本地准备**；能否最终产出 APK 取决于
  GitHub Actions 上的实际编译结果。

---

## 7. 拿到 APK 之后的安装

```bash
adb install -r app-debug.apk
```

手机需先打开 USB 调试并授权本机；若为覆盖安装且旧包签名不一致，请先卸载旧包再安装。
